<template>
  <div
    ref="scrollerRef"
    class="virtual-scroller"
    @scroll="handleScroll"
  >
    <div class="virtual-list-wrapper">
      <div
        v-for="(item, index) in items"
        :key="getItemKey(item, index)"
        :ref="el => setItemRef(el, index)"
        class="virtual-item-container"
        :style="getItemStyle(index)"
      >
        <slot
          v-if="isItemRendered(index)"
          :item="item"
          :index="index"
        ></slot>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useConversationStore } from '@/stores/conversation'

const props = withDefaults(
  defineProps<{
    items: any[]
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

watch(
  () => conversationStore.isMessageLoading,
  (loading) => {
    if (loading) {
      initialScrollPending.value = true
    } else {
      // 200ms 内保持强力贴底锁死，防范 DOM 延迟撑开
      setTimeout(() => {
        initialScrollPending.value = false
      }, 200)
    }
  },
  { immediate: true }
)

// 锁，用于防止高度更新时的重入冲突
let isUpdatingHeights = false

// 根据 index 获取该 item 的 Key
const getItemKey = (item: any, index: number) => {
  return item.id !== undefined ? item.id : index
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
    return {
      minHeight: props.estimatedItemSize + 'px'
    }
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
  scrollTop.value = scrollerRef.value.scrollTop
  clientHeight.value = scrollerRef.value.clientHeight
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

    // 执行滚动条高度补偿，防范向上滚动白屏/抖动
    if (scrollTopAdjustment !== 0 && scrollerRef.value) {
      scrollerRef.value.scrollTop += scrollTopAdjustment
      // 瞬时同步数据，防止 nextTick 延迟抖动
      scrollTop.value = scrollerRef.value.scrollTop
    }

    if (hasChanges || initialScrollPending.value) {
      // 异步刷新滚动高度
      nextTick(() => {
        handleScroll()
        if (initialScrollPending.value) {
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

// 自动追踪触底逻辑：只要原本处于底部，在有任何数据变动时就瞬时定位贴底，杜绝抖动
watch(
  () => props.items,
  (newVal) => {
    if (!scrollerRef.value || newVal.length === 0) return

    const threshold = 120 // 容差像素值
    const isAtBottom =
      scrollerRef.value.scrollHeight -
        scrollerRef.value.scrollTop -
        scrollerRef.value.clientHeight <
      threshold

    if (isAtBottom || initialScrollPending.value) {
      // 统一瞬间定位贴底
      scrollToBottom(false)
    }
  },
  { deep: true }
)

// 初始化生命周期
onMounted(() => {
  if (scrollerRef.value) {
    clientHeight.value = scrollerRef.value.clientHeight
    scrollTop.value = scrollerRef.value.scrollTop
  }
  initResizeObserver()
  // 挂载后立即强力贴底
  scrollToBottom(false)
})

onBeforeUnmount(() => {
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
  /* 移入消息列表的整体边距 */
  padding: 20px;
}

.virtual-list-wrapper {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.virtual-item-container {
  width: 100%;
  box-sizing: border-box;
  /* 用 padding-bottom 代替 gap，确保消息间距能够被 ResizeObserver 物理测量并纳入高度计算 */
  padding-bottom: 20px;
}
</style>
