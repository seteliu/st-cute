<template>
  <div class="login-wrapper">
    <div class="login-glow"></div>
    <div class="login-waves-container">
      <div class="wave wave1"></div>
      <div class="wave wave2"></div>
      <div class="wave wave3"></div>
    </div>
    <div class="login-card">
      <div class="login-header">
        <h2 class="login-title">ST-Cute</h2>
        <p class="login-subtitle">AI 编程协作智能体终端</p>
      </div>

      <n-form ref="formRef" :model="formValue" :rules="rules" @submit.prevent="handleLogin">
        <n-form-item path="password" label="安全密码" :show-label="false">
          <n-input
            v-model:value="formValue.password"
            type="password"
            show-password-on="click"
            placeholder="请输入安全访问密码"
            size="large"
            class="premium-input"
            :bordered="false"
            maxlength="64"
          />
        </n-form-item>

        <n-button
          type="primary"
          block
          size="large"
          :loading="loading"
          :disabled="loading"
          class="login-btn"
          @click="handleLogin"
        >
          登 录
        </n-button>
      </n-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<any>(null)
const loading = ref(false)
const formValue = ref({
  password: ''
})

const rules = {
  password: {
    required: true,
    message: '请输入访问控制密码',
    trigger: ['input', 'blur']
  }
}

const handleLogin = () => {
  if (loading.value) return
  loading.value = true

  formRef.value?.validate(async (errors: any) => {
    if (errors) {
      loading.value = false
      return
    }
    try {
      await userStore.login(formValue.value.password)
      if ((window as any).$message) {
        ;(window as any).$message.success('登录成功')
      }
      // replace 替代 push：登录成功后不留历史栈记录，防止后退键回到登录页
      router.replace('/')
    } catch (err: any) {
      console.error('登录校验失败:', err)
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-wrapper {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100vw;
  height: 100vh;  /* 回退：不支持小视口单位的环境 */
  height: 100svh; /* 移动端小视口：按地址栏展开时的最小可视区域取高，避免登录页底部被截断 */
  background: radial-gradient(circle at center, #1b2838 0%, #0d131a 100%);
  overflow: hidden;
  perspective: 1200px; /* 开启 3D 透视 */
}

.login-glow {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 700px;
  height: 700px;
  background: radial-gradient(circle, rgba(129, 182, 229, 0.1) 0%, transparent 70%);
  z-index: 1;
  pointer-events: none;
}

.login-waves-container {
  position: absolute;
  top: 62%; /* 下移，做在登录框底下承托 */
  left: 50%;
  transform: translate(-50%, -50%) rotateX(75deg); /* 水平放平水波纹，产生 3D 透视 */
  transform-style: preserve-3d;
  width: 800px;
  height: 800px;
  z-index: 1;
  pointer-events: none;
  display: flex;
  justify-content: center;
  align-items: center;
}

.wave {
  position: absolute;
  border-radius: 50%;
  border: 1.5px solid rgba(129, 182, 229, 0.4);
  box-shadow: 0 0 40px rgba(129, 182, 229, 0.35), inset 0 0 25px rgba(129, 182, 229, 0.2);
  width: 100%;
  height: 100%;
  opacity: 0;
  animation: waveRipple 10s cubic-bezier(0.1, 0.8, 0.25, 1) infinite;
}

.wave1 {
  animation-delay: 0s;
}

.wave2 {
  animation-delay: 3.3s;
}

.wave3 {
  animation-delay: 6.6s;
}

@keyframes waveRipple {
  0% {
    width: 150px;
    height: 150px;
    opacity: 0.95;
  }
  50% {
    opacity: 0.6;
  }
  100% {
    width: 850px;
    height: 850px;
    opacity: 0;
  }
}

.login-card {
  position: relative;
  z-index: 2;
  width: 380px;
  padding: 40px;
  background: rgba(20, 20, 25, 0.72);
  backdrop-filter: blur(25px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.65);
  animation: fadeIn 0.8s ease-out;
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-title {
  font-size: 32px;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 8px 0;
  letter-spacing: 2px;
}

.login-subtitle {
  font-size: 14px;
  color: #767c82;
  margin: 0;
}

.premium-input {
  border-radius: 8px;
  background-color: rgba(16, 16, 20, 0.6) !important;
}

.login-btn {
  margin-top: 10px;
  border-radius: 8px;
  font-weight: 600;
  letter-spacing: 4px;
  transition: all 0.3s ease;
  background: linear-gradient(135deg, #81b6e5, #659fcb);
  border: none;
  color: #ffffff !important;
}

.login-btn:hover {
  background: linear-gradient(135deg, #9ec7eb, #81b6e5);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(129, 182, 229, 0.3);
  color: #ffffff !important;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
