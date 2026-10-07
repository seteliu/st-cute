<template>
  <div ref="scrollerRef" class="virtual-scroller" @scroll="handleScroll">
    <div class="virtual-list-wrapper">
      <div
        v-for="(item, index) in items"
        :key="getItemKey(item, index)"
        :ref="(el) => setItemRef(el, index)"
        class="virtual-item-container"
        :style="getItemStyle(index)"
      >
        <slot v-if="isItemRendered(index)" :item="item" :index="index"></slot>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import type { VirtualListItem } from '@/utils/foldEngine'

// items 类型收敛：items 元素类型 = RenderItem（foldEngine 单点维护，新条目形态在
// VirtualListItem 别名处扩展），替代历史 any[]，让插槽与 key 计算获得类型约束
const props = withDefaults(
  defineProps<{
    items: VirtualListItem[]
    estimatedItemSize?: number
    buffer?: number
  }>(),
  {
    estimatedItemSize: 120,
    buffer: 8
  }
)

const scrollerRef = ref<HTMLElement | null>(null)
const itemRefs = ref<Record<number, HTMLElement>>({})

// 高度缓存 Map: index -> height
const heights = ref<Record<number, number>>({})

// 滚动状态
const scrollTop = ref(0)
const clientHeight = ref(0)

const conversationStore = useConversationStore()

// 强力贴底锁定磁铁
const initialScrollPending = ref(true)
// 贴底锁定计时器句柄：锁存续期内高度仍在收敛时重置计时，防范大会话首屏收敛慢导致锁提前释放
let scrollLockTimer: ReturnType<typeof setTimeout> | null = null

// 续期式贴底锁：holdForever 为 true 时锁定但不启动计时（加载期间保持强锁），否则静默 200ms 后释放
const armScrollLock = (holdForever = false) => {
  initialScrollPending.value = true
  if (scrollLockTimer) {
    clearTimeout(scrollLockTimer)
    scrollLockTimer = null
  }
  if (!holdForever) {
    scrollLockTimer = setTimeout(() => {
      initialScrollPending.value = false
      scrollLockTimer = null
    }, 200)
  }
}

watch(
  () => conversationStore.isMessageLoading,
  (loading) => {
    if (loading) {
      // 加载期间保持强锁并清掉历史计时器，杜绝快速切换会话时旧计时器提前解锁
      armScrollLock(true)
    } else {
      // 200ms 内保持强力贴底锁死，防范 DOM 延迟撑开；期间高度仍在收敛则由 ResizeObserver 持续续期
      armScrollLock()
    }
  },
  { immediate: true }
)

// 距底判定：距底 10px 容差内视为用户仍停留在底部
const isNearBottom = () => {
  const el = scrollerRef.value
  if (!el) return false
  return el.scrollHeight - el.scrollTop - el.clientHeight < 10
}

// ------------------------------------------------------------------
// 宽度变化重锚定：宽度突变（断点切换侧栏抽屉化/还原、拖拽侧栏、拉伸窗口）会让
// 全部消息重新换行，旧宽度下实测的高度缓存整体失效。若不清空，虚拟偏移表混用
// 新旧高度导致可视切片错位，叠加逐项高度补偿与浏览器 clamp，表现为滚动位置
// 漂移甚至跳顶（复现于窄桌面还原三栏时）。
// 处理：宽度变化当帧立即进入「重锚定」状态——按旧布局捕获锚点（视口首项/是否贴底）
// 并清空缓存，抢在旧高度参与补偿运算之前止血；重锚定期间抑制一切滚动迁移
// （高度补偿/贴底校准），待新宽度下高度测量收敛后统一恢复到锚点位置
// ------------------------------------------------------------------

// 上一次观测到的滚动容器宽度（用于过滤同值触发的 Observer 回调）
let lastObservedWidth = 0
// 重锚定进行中标志：true 时逐项高度只记录不迁移滚动。
// 用 ref 而非普通变量：visibleStartIndex 的早退分支需依赖它做响应式失效，
// 否则解冻后该 computed 会因早退而缓存住锚点索引，渲染窗口不再跟随滚动
const isWidthResettling = ref(false)
// 重锚定锚点：宽度变化前视口首项索引
let reanchorIndex = -1
// 锚点条目的稳定 key（与 getItemKey 同源）：抑制窗口内渲染序列可能被流式折叠重组
// （foldEngine 动态折叠会把已完成工具组并入折叠块，索引随之失配），故同时记录 key，
// 恢复时优先按 key 找回条目当前索引，查不到再回退捕获时索引
let reanchorKey: number | string | null = null
// 重锚定前用户是否停留在底部：贴底则恢复为贴底，否则恢复锚点消息贴顶
let reanchorAtBottom = false
// 重锚定落定计时器：连续宽度变化（拖拽侧栏/窗口边缘）只在静默 120ms 后恢复一次
let widthSettleTimer: ReturnType<typeof setTimeout> | null = null
// 重锚定世代令牌：每次宽度变化即自增，落定恢复链在入口、恢复前、解除抑制前三处校验，
// 防止上一次恢复的延迟帧（rAF 链较长）在新一轮重锚定期间提前解除抑制、跳过新锚点恢复
let resettleGeneration = 0
// 落定恢复链的 rAF 句柄集合：卸载时统一取消，避免回调在组件销毁后继续执行
const pendingResettleFrames = new Set<number>()
// 观测滚动容器自身宽度的 Observer 实例
let scrollerWidthObserver: ResizeObserver | null = null

// 登记并执行一个 rAF：句柄入集合便于卸载时统一取消
const scheduleResettleFrame = (callback: () => void) => {
  const frameId = requestAnimationFrame(() => {
    pendingResettleFrames.delete(frameId)
    callback()
  })
  pendingResettleFrames.add(frameId)
}

/**
 * 重锚定落定：宽度停止变化 120ms 后，新布局下的逐项高度已测量收敛，
 * 此时统一恢复滚动位置——贴底用户磁吸回底部，翻阅历史的用户回到锚点消息贴顶，
 * 恢复完成后再解除抑制，让既有的高度补偿/贴底校准机制重新接管
 */
const finishWidthResettle = (generation: number) => {
  widthSettleTimer = null
  // 世代已过期（期间又发生了新的宽度变化）：本轮恢复作废，交由新一轮处理
  if (!isWidthResettling.value || generation !== resettleGeneration) return
  nextTick(() => {
    scheduleResettleFrame(() => {
      // 等一帧：落定前高度尚在测量中，offsets 尚未收敛
      scheduleResettleFrame(() => {
        if (!isWidthResettling.value || generation !== resettleGeneration) return
        if (reanchorAtBottom) {
          scrollToBottom(false)
        } else {
          // 锚点索引可能因抑制窗口内流式折叠重组而失配，优先按稳定 key 找回当前索引
          const targetIndex =
            reanchorKey !== null ? findIndexByKey(reanchorKey, reanchorIndex) : reanchorIndex
          if (targetIndex >= 0) {
            scrollToIndex(targetIndex, false)
          }
        }
        // 再等一帧确保恢复滚动已生效，随后解除抑制；解除后立即回同步一次虚拟滚动位置——
        // 恢复滚动产生的原生 scroll 事件在抑制期内被吞掉，此处补齐最终同步，
        // 确保渲染窗口切片与实际滚动位置严格一致
        scheduleResettleFrame(() => {
          if (generation !== resettleGeneration) return
          isWidthResettling.value = false
          // 解冻后回写一次滚动位置：把抑制期被冻结的最终 scrollTop 同步进虚拟状态，
          // 使渲染窗口切片与实际滚动位置对齐
          handleScroll()
        })
      })
    })
  })
}

// 按稳定 key 在当前渲染序列中定位条目索引；查不到时回退传入的旧索引
const findIndexByKey = (key: number | string, fallbackIndex: number): number => {
  const idx = props.items.findIndex((item, index) => getItemKey(item, index) === key)
  return idx !== -1 ? idx : fallbackIndex
}

// 宽度变化入口（Observer 当帧同步调用）：首帧变化时立即捕获锚点并清空高度缓存，
// 抢在旧高度参与补偿运算之前止血；连续变化仅续期落定计时器，避免拖拽过程反复重建
const handleScrollerWidthChange = () => {
  const el = scrollerRef.value
  if (!el) return
  // 每次宽度变化都自增世代：使上一轮可能仍在 rAF 链中的恢复流程立即作废，
  // 否则过期链条会在新一轮重锚定中途解除抑制，导致新锚点恢复被跳过
  resettleGeneration += 1
  if (!isWidthResettling.value) {
    // 先读锚点再置位抑制标志：置位会让 visibleStartIndex 命中重锚定早退分支，
    // 若先置位后读取且该 computed 恰处于失效态，会拿到上一轮的残留锚点
    reanchorIndex = visibleStartIndex.value
    const anchorItem = reanchorIndex < props.items.length ? props.items[reanchorIndex] : undefined
    reanchorKey = anchorItem !== undefined ? getItemKey(anchorItem, reanchorIndex) : null
    // 贴底锁激活时一并视为「应贴底」：加载/贴底锁存期间列表尚未撑开，
    // isNearBottom 可能失真，若不加此条件会把正在加载的会话误判为翻阅历史
    reanchorAtBottom = isNearBottom() || initialScrollPending.value
    isWidthResettling.value = true
  }
  // 高度缓存随每次宽度变化全量失效，不与锚点捕获绑在同一分支：连续轮次间隔内
  // 新实测的已是新宽度高度，旧残留混入 offsets 会造成恢复落点偏移
  heights.value = {}
  lastObservedWidth = el.clientWidth
  if (widthSettleTimer) {
    clearTimeout(widthSettleTimer)
  }
  const generation = resettleGeneration
  widthSettleTimer = setTimeout(() => finishWidthResettle(generation), 120)
}

// 初始化滚动容器自身宽度观测：仅宽度变化具有 reflow 语义（纵向拉伸不影响换行），忽略高度
const initScrollerWidthWatcher = () => {
  if (typeof window === 'undefined' || !window.ResizeObserver || !scrollerRef.value) return
  lastObservedWidth = scrollerRef.value.clientWidth
  scrollerWidthObserver = new window.ResizeObserver((entries) => {
    for (const entry of entries) {
      if ((entry.target as HTMLElement).clientWidth !== lastObservedWidth) {
        handleScrollerWidthChange()
      }
    }
  })
  scrollerWidthObserver.observe(scrollerRef.value)
}

// 锁，用于防止高度更新时的重入冲突
let isUpdatingHeights = false

// 根据 RenderItem 变体提取稳定 key：后端消息 id 唯一（SQLite 自增），折叠块 id 取区间
// 最小 id，孤儿工具组为单例 'orphan_tools'，truncated_tip 全列表至多一个用静态字符串。
// default 兜底 index：防御未来新增变体漏配 key 时仍可渲染（与类型单点扩展设计一致）
const getItemKey = (item: VirtualListItem, index: number): number | string => {
  switch (item.type) {
    case 'message':
      return item.data.id
    case 'folded':
      return item.folded.id
    case 'tool_group':
      return item.parentMessageId
    case 'truncated_tip':
      return 'truncated-tip'
    default:
      return index
  }
}

// 缓存 DOM 引用
const setItemRef = (el: any, index: number) => {
  if (el) {
    itemRefs.value[index] = el as HTMLElement
  } else {
    delete itemRefs.value[index]
  }
}

// 预估或返回缓存的高度
const getItemHeight = (index: number) => {
  return heights.value[index] || props.estimatedItemSize
}

// 样式设置：渲染时保持自适应，占位时强制高度撑开
const getItemStyle = (index: number) => {
  if (isItemRendered(index)) {
    // 渲染时由内容自然撑高：不强设 minHeight，避免折叠卡片等小体量消息被估算高度硬撑出大片空白，同时防止假高度污染实测缓存
    return {}
  } else {
    return {
      height: getItemHeight(index) + 'px',
      overflow: 'hidden'
    }
  }
}

// 累计高度数组，用于二分查找
const offsets = computed(() => {
  const list: number[] = []
  let total = 0
  for (let i = 0; i < props.items.length; i++) {
    list.push(total)
    total += getItemHeight(i)
  }
  return list
})

// 计算可视区间首项索引
const visibleStartIndex = computed(() => {
  // 宽度重锚定时 heights 已清空、新宽度下高度尚未测出，按旧 scrollTop 二分只能得到
  // 基于估算高度的错位索引（冻结期不更新 scrollTop，故该计算不会被触发修正）。
  // 此时直接返回宽度变化前捕获的锚点索引，保证冻结期渲染窗口稳定在用户原本观看的位置。
  // isWidthResettling 为 ref，其翻转本身即会让本 computed 失效重算，无需依赖解冻处的 scrollTop 回写
  if (isWidthResettling.value && reanchorIndex >= 0) {
    return reanchorIndex
  }
  const top = scrollTop.value
  const list = offsets.value
  let low = 0
  let high = list.length - 1
  while (low <= high) {
    const mid = Math.floor((low + high) / 2)
    if (list[mid] === top) {
      return mid
    } else if (list[mid] < top) {
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return Math.max(0, low - 1)
})

// 计算可视区间尾项索引
const visibleEndIndex = computed(() => {
  // 宽度重锚定期间：offsets 全为估算高度、与冻结的 scrollTop 不同源，二分结果不可信
  // （scrollTop 较大时 low 会一路越过列表末尾，直接把渲染区间撑到尾部、渲染上百项）。
  // 改为「锚点 + 按视口高度估算的条目数」这一稳定小窗口，冻结点渲染量可控
  if (isWidthResettling.value && reanchorIndex >= 0) {
    const roughCount = Math.ceil(clientHeight.value / props.estimatedItemSize) + 1
    return Math.min(props.items.length - 1, reanchorIndex + roughCount)
  }
  const bottom = scrollTop.value + clientHeight.value
  const list = offsets.value
  let low = visibleStartIndex.value
  let high = list.length - 1
  while (low <= high) {
    const mid = Math.floor((low + high) / 2)
    if (list[mid] === bottom) {
      return mid
    } else if (list[mid] < bottom) {
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return Math.min(props.items.length - 1, low)
})

// 带 Buffer 的渲染起止索引
const startIndex = computed(() => {
  return Math.max(0, visibleStartIndex.value - props.buffer)
})

const endIndex = computed(() => {
  return Math.min(props.items.length - 1, visibleEndIndex.value + props.buffer)
})

// 判定某一项是否需要被渲染真实 DOM 树
const isItemRendered = (index: number) => {
  return index >= startIndex.value && index <= endIndex.value
}

// 滚动事件处理
const handleScroll = () => {
  if (!scrollerRef.value) return
  clientHeight.value = scrollerRef.value.clientHeight
  // 宽度重锚定期间冻结虚拟滚动位置同步：清缓存瞬间浏览器会钳制/重置原生 scrollTop，
  // 此时同步会让渲染窗口错移到列表顶部造成「跳顶闪现」；落定恢复锚点后由解冻逻辑统一回同步
  if (!isWidthResettling.value) {
    scrollTop.value = scrollerRef.value.scrollTop
  }
}

// ResizeObserver 统一实例
let resizeObserver: ResizeObserver | null = null

// 核心：利用 ResizeObserver 实时测量渲染子项的物理高度，防范 Markdown 和卡片撑开高度改变引起的计算偏移错误
const initResizeObserver = () => {
  if (typeof window === 'undefined' || !window.ResizeObserver) return

  resizeObserver = new window.ResizeObserver((entries) => {
    if (isUpdatingHeights) return
    isUpdatingHeights = true

    let hasChanges = false
    // 本轮是否发生真实高度变化（含最后一项）：用于贴底锁续期与锁外贴底校准
    let lastItemChanged = false
    let scrollTopAdjustment = 0
    const currentStartIndex = visibleStartIndex.value

    for (const entry of entries) {
      const target = entry.target as HTMLElement
      // 遍历 itemRefs 寻找匹配该节点的 index
      const found = Object.entries(itemRefs.value).find(([_, el]) => el === target)
      if (found) {
        const index = Number(found[0])
        // 判断当前该项是否为真实渲染状态（非真实渲染时，高度不应受 Observer 干扰）
        if (isItemRendered(index)) {
          const height = target.getBoundingClientRect().height
          if (height > 0 && heights.value[index] !== height) {
            const oldHeight = getItemHeight(index)
            const diff = height - oldHeight
            heights.value[index] = height
            lastItemChanged = true

            // 如果高度发生改变的项在当前可视区域首项的上方，累加调整高度以补偿滚动条位置
            if (index < currentStartIndex) {
              scrollTopAdjustment += diff
            }

            // 只要不是最底部的气泡，才允许重新计算可视区索引以防抖动
            if (index !== props.items.length - 1) {
              hasChanges = true
            }
          }
        }
      }
    }

    // 执行滚动条高度补偿，防范向上滚动白屏/抖动。
    // 宽度重锚定期间抑制：旧高度已清空、新高度未收敛，此时迁移滚动必错位（跳顶根因之一）
    if (scrollTopAdjustment !== 0 && scrollerRef.value && !isWidthResettling.value) {
      scrollerRef.value.scrollTop += scrollTopAdjustment
      // 瞬时同步数据，防止 nextTick 延迟抖动
      scrollTop.value = scrollerRef.value.scrollTop
    }

    if (hasChanges || lastItemChanged || initialScrollPending.value) {
      // 贴底锁仍激活且本轮高度仍在收敛：续期锁存，渲染静默 200ms 后才释放，防范大会话首屏收敛慢被提前解锁
      if (initialScrollPending.value && (hasChanges || lastItemChanged)) {
        armScrollLock()
      }
      // 异步刷新滚动高度
      nextTick(() => {
        handleScroll()
        if (isWidthResettling.value) {
          // 宽度重锚定期间只刷新偏移数据，不做任何滚动迁移；待落定后统一恢复锚点
          return
        }
        if (initialScrollPending.value) {
          scrollToBottom(false)
        } else if (lastItemChanged && isNearBottom()) {
          // 锁释放后最后一项仍被撑高（大 Markdown 渐进渲染等）：用户停留在底部附近时校准贴底，杜绝底部多出一截；
          // 向上翻阅历史（距底远）时不打扰
          scrollToBottom(false)
        }
      })
    }
    isUpdatingHeights = false
  })

  // 观察所有已被引用的 wrapper
  watch(
    () => itemRefs.value,
    (refs) => {
      if (!resizeObserver) return
      resizeObserver.disconnect()
      Object.values(refs).forEach((el) => {
        if (el) resizeObserver!.observe(el)
      })
    },
    { deep: true, immediate: true }
  )
}

// 平滑触底与定位控制
const scrollToBottom = (smooth = true) => {
  nextTick(() => {
    if (!scrollerRef.value) return
    // 强制将滚动条置为最底部
    scrollerRef.value.scrollTo({
      top: scrollerRef.value.scrollHeight,
      behavior: smooth ? 'smooth' : 'auto'
    })
    // 立即校对一次高度
    handleScroll()
  })
}

// 定位到指定 item 的索引位置
const scrollToIndex = (index: number, smooth = true) => {
  nextTick(() => {
    if (!scrollerRef.value || index < 0 || index >= props.items.length) return
    const offset = offsets.value[index]
    scrollerRef.value.scrollTo({
      top: offset,
      behavior: smooth ? 'smooth' : 'auto'
    })
  })
}

// 自动追踪触底逻辑：只要原本处于底部，在有任何数据变动时就瞬时定位贴底，杜绝抖动。
// 宽度重锚定期间抑制：此时贴底会基于未收敛的偏移表乱跳，落定后由锚点恢复统一处理
watch(
  () => props.items,
  (newVal) => {
    if (!scrollerRef.value || newVal.length === 0) return
    if (isWidthResettling.value) return

    // 触底判定：必须完全在底部（距底 10px 容差内）才视为处于底部并自动维持贴底，
    // 避免用户稍向上翻阅历史消息时被流式输出强行拉回底部（复用 isNearBottom 判定）
    const isAtBottom = isNearBottom()

    if (isAtBottom || initialScrollPending.value) {
      // 统一瞬间定位贴底
      scrollToBottom(false)
    }
  },
  { deep: true }
)

// 切换会话时清空高度缓存：旧会话的实测高度对新会话是脏数据，会污染渲染区间计算导致贴底落点偏移
watch(
  () => conversationStore.activeCid,
  () => {
    heights.value = {}
  }
)

// 初始化生命周期
onMounted(() => {
  if (scrollerRef.value) {
    clientHeight.value = scrollerRef.value.clientHeight
    scrollTop.value = scrollerRef.value.scrollTop
  }
  initResizeObserver()
  // 观测滚动容器宽度：宽度突变时清空高度缓存并重新锚定（详见 handleScrollerWidthChange 注释）
  initScrollerWidthWatcher()
  // 挂载后立即强力贴底
  scrollToBottom(false)
})

onBeforeUnmount(() => {
  // 清理贴底锁定计时器，防范卸载后写入已销毁组件状态
  if (scrollLockTimer) {
    clearTimeout(scrollLockTimer)
    scrollLockTimer = null
  }
  // 清理宽度落定防抖计时器、在途 rAF 链与宽度观测器
  if (widthSettleTimer) {
    clearTimeout(widthSettleTimer)
    widthSettleTimer = null
  }
  pendingResettleFrames.forEach((frameId) => cancelAnimationFrame(frameId))
  pendingResettleFrames.clear()
  if (scrollerWidthObserver) {
    scrollerWidthObserver.disconnect()
    scrollerWidthObserver = null
  }
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})

// 暴露公共方法至父组件
defineExpose({
  scrollToBottom,
  scrollToIndex
})
</script>

<style scoped>
.virtual-scroller {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  position: relative;
  box-sizing: border-box;

  /* 隐藏默认的原生闪烁，提升虚拟列表平滑滑动手感 */
  scroll-behavior: auto;

  /* 禁用浏览器原生滚动锚定，由虚拟列表的高度补偿机制接管，解决滚动抖动与白屏 */
  overflow-anchor: none;

  /* 左右边距由子项容器承担（保证与底部输入框区 24px 对齐），此处仅保留上下边距，避免滚动条宽度叠加导致左右视觉间隙不一致 */
  padding: 20px 0;
}

.virtual-list-wrapper {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.virtual-item-container {
  width: 100%;
  box-sizing: border-box;

  /* 左右各 24px，与输入框区（.chat-footer padding 24px）严格对齐；用 padding-bottom 代替 gap，确保消息间距能够被 ResizeObserver 物理测量并纳入高度计算 */
  padding: 0 24px 20px;
}
</style>
