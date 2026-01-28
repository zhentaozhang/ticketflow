<template>
  <div class="login-page">
    <Header></Header>
    <div class="login-container">
      <div class="login-card">
        <!-- 左侧 2026 Brand Showcase 面板 -->
        <div class="login-showcase">
          <router-link to="/index" class="back-home-btn">
            <el-icon><ArrowLeft /></el-icon>
            <span>返回首页</span>
          </router-link>
          <div class="login-showcase__overlay"></div>
          <div class="login-showcase__content">
            <div class="brand-badge">
              <span class="dot"></span> TicketFlow 官方票务平台
            </div>
            <h1 class="showcase-title">为热爱 · 赴现场</h1>
            <p class="showcase-subtitle">海量热门演唱会、话剧、体育赛事极速在线选座购票</p>
            
            <div class="feature-pills">
              <div class="feature-pill">
                <span class="icon">⚡</span>
                <div class="txt">
                  <strong>毫秒级抢票引擎</strong>
                  <span>高并发秒级配票体系</span>
                </div>
              </div>
              <div class="feature-pill">
                <span class="icon">🎟️</span>
                <div class="txt">
                  <strong>智能电子票包</strong>
                  <span>无需取票 扫码快速入场</span>
                </div>
              </div>
              <div class="feature-pill">
                <span class="icon">🔒</span>
                <div class="txt">
                  <strong>100% 实名保真</strong>
                  <span>一票一证 购票保障</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧登录表单面板 -->
        <div class="login-form-panel">
          <h2 class="form-title">欢迎登录</h2>
          <el-tabs v-model="activeName" class="login-tabs">
            <el-tab-pane label="密码登录" name="first">
              <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form" label-position="top">
                <div class="error-tips" v-if="isTips">
                  <el-icon color="#F56C6C"><WarningFilled /></el-icon>
                  <span>{{ tipsContent }}</span>
                </div>
                
                <el-form-item label="账号" prop="userName">
                  <el-input v-model="userName" placeholder="手机号 / 邮箱地址" size="large" :prefix-icon="User" />
                </el-form-item>

                <el-form-item label="密码" prop="password">
                  <el-input
                    type="password"
                    show-password
                    v-model="loginForm.password"
                    placeholder="请输入登录密码"
                    size="large"
                    :prefix-icon="Lock"
                    @keyup.enter="handleLogin"
                  />
                </el-form-item>

                <el-button
                  :loading="loading"
                  size="large"
                  type="primary"
                  class="submit-btn"
                  @click.prevent="handleLogin"
                >
                  <span v-if="!loading">登 录</span>
                  <span v-else>登 录 中...</span>
                </el-button>

                <div class="form-footer">
                  <span>还没有账号？</span>
                  <router-link to="/register" class="register-link">免费注册 →</router-link>
                </div>
              </el-form>
            </el-tab-pane>
            <el-tab-pane label="短信登录" name="second">
              <div class="empty-tab-tip">短信快捷登录功能筹备中</div>
            </el-tab-pane>
            <el-tab-pane label="扫码登录" name="third">
              <div class="empty-tab-tip">二维码扫码登录功能筹备中</div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </div>
    <Footer></Footer>
  </div>
</template>

<script setup lang="ts">
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import { isPhoneNumber, isEmailAddress } from '@/utils/index'
import { ref, getCurrentInstance } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/modules/auth'
import { useRouter } from 'vue-router'
import { CHANNEL_CODE } from '@/utils/constants'
import { User, Lock, WarningFilled, ArrowLeft } from '@element-plus/icons-vue'

interface LoginForm {
  email: string
  mobile: string
  password: string
  code: string
}

const userStore = useAuthStore()
const router = useRouter()
const loading = ref<boolean>(false)
const activeName = ref<string>('first')
const register = ref<boolean>(true)
const isTips = ref<boolean>(false)
const tipsContent = ref<string>('')
const { proxy } = getCurrentInstance()!

const userName = ref<string>('')
const loginForm = ref<LoginForm>({
  email: '',
  mobile: '',
  password: '',
  code: CHANNEL_CODE,
})

const loginRules = ref({})

const handleClick = (_tab: unknown, _event: unknown): void => {}

function handleLogin(): void {
  loginForm.value.email = ''
  loginForm.value.mobile = ''
  if (isEmailAddress(userName.value)) {
    loginForm.value.email = userName.value
  } else if (isPhoneNumber(userName.value)) {
    loginForm.value.mobile = userName.value
  } else {
    isTips.value = true
    tipsContent.value = '请输入正确的手机号或邮箱'
    return
  }

  proxy.$refs.loginRef.validate((valid: boolean) => {
    if (valid) {
      loading.value = true
      userStore.login(loginForm.value).then(() => {
        loading.value = false
        const target = (route.query.redirect as string) || '/'
        router.push({ path: target })
      }).catch((response: any) => {
        loading.value = false
        isTips.value = true
        tipsContent.value = response.message || '登录失败'
      })
    }
  })
}
</script>

<style scoped lang="scss">
.login-page {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;
}

.login-container {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px 20px;
}

.login-card {
  width: 960px;
  min-height: 560px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-lg);
  display: flex;
  overflow: hidden;
}

/* 左侧 Brand Showcase 面板 */
.login-showcase {
  flex: 1;
  background: linear-gradient(135deg, #0F172A 0%, #1E1B4B 50%, #312E81 100%);
  position: relative;
  padding: 48px 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  color: #fff;
  overflow: hidden;

  .back-home-btn {
    position: absolute;
    top: 24px;
    left: 24px;
    z-index: 20;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 14px;
    background: rgba(255, 255, 255, 0.12);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: var(--tf-radius-full);
    color: #ffffff !important;
    font-size: 13px;
    font-weight: 600;
    text-decoration: none;
    transition: all 0.25s;
    cursor: pointer;

    &:hover {
      background: rgba(255, 255, 255, 0.25);
      border-color: rgba(255, 255, 255, 0.4);
      transform: translateX(-3px);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
    }
  }

  &__overlay {
    position: absolute;
    top: -50%;
    left: -50%;
    width: 200%;
    height: 200%;
    background: radial-gradient(circle, rgba(255, 55, 29, 0.18) 0%, transparent 60%);
    pointer-events: none;
  }

  &__content {
    position: relative;
    z-index: 10;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .brand-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 14px;
    background: rgba(255, 255, 255, 0.1);
    backdrop-filter: blur(12px);
    border-radius: var(--tf-radius-full);
    font-size: 13px;
    width: fit-content;
    color: #E2E8F0;

    .dot {
      width: 8px;
      height: 8px;
      background: var(--tf-primary);
      border-radius: 50%;
      box-shadow: 0 0 8px var(--tf-primary);
    }
  }

  .showcase-title {
    font-size: 32px;
    font-weight: 800;
    margin: 0;
    letter-spacing: 1px;
    background: linear-gradient(135deg, #FFFFFF 0%, #CBD5E1 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
  }

  .showcase-subtitle {
    font-size: 14px;
    color: #94A3B8;
    margin: 0;
    line-height: 1.6;
  }

  .feature-pills {
    margin-top: 12px;
    display: flex;
    flex-direction: column;
    gap: 14px;

    .feature-pill {
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 12px 16px;
      background: rgba(255, 255, 255, 0.06);
      backdrop-filter: blur(10px);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: var(--tf-radius-md);

      .icon {
        font-size: 22px;
      }

      .txt {
        display: flex;
        flex-direction: column;
        gap: 2px;

        strong {
          font-size: 14px;
          color: #F8FAFC;
        }

        span {
          font-size: 12px;
          color: #94A3B8;
        }
      }
    }
  }
}

/* 右侧表单面板 */
.login-form-panel {
  width: 440px;
  padding: 48px 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;

  .form-title {
    font-size: 22px;
    font-weight: 700;
    color: var(--tf-text-primary);
    margin: 0 0 20px 0;
  }

  .login-tabs {
    :deep(.el-tabs__header) {
      border-bottom: 1px solid var(--tf-border);
      margin-bottom: 24px;
    }

    :deep(.el-tabs__item) {
      font-size: 15px;
      font-weight: 600;
      color: var(--tf-text-secondary);

      &.is-active {
        color: var(--tf-primary);
      }
    }
  }

  .error-tips {
    display: flex;
    align-items: center;
    gap: 8px;
    background: #FEF2F2;
    border: 1px solid #FCA5A5;
    color: #DC2626;
    padding: 10px 14px;
    border-radius: var(--tf-radius-sm);
    font-size: 13px;
    margin-bottom: 16px;
  }

  .submit-btn {
    width: 100%;
    height: 46px;
    font-size: 16px;
    font-weight: 600;
    border-radius: var(--tf-radius-full);
    margin-top: 12px;
  }

  .form-footer {
    display: flex;
    justify-content: center;
    gap: 6px;
    font-size: 14px;
    color: var(--tf-text-secondary);
    margin-top: 20px;

    .register-link {
      color: var(--tf-primary);
      font-weight: 600;
      text-decoration: none;
      &:hover { text-decoration: underline; }
    }
  }

  .empty-tab-tip {
    padding: 40px 0;
    text-align: center;
    color: var(--tf-text-secondary);
    font-size: 14px;
  }
}
</style>
