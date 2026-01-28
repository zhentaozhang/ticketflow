<template>
  <div class="register-page">
    <Header></Header>
    <div class="register-container">
      <div class="register-card">
        <!-- 左侧 Brand Showcase 面板 -->
        <div class="register-showcase">
          <router-link to="/index" class="back-home-btn">
            <el-icon><ArrowLeft /></el-icon>
            <span>返回首页</span>
          </router-link>
          <div class="register-showcase__overlay"></div>
          <div class="register-showcase__content">
            <div class="brand-badge">
              <span class="dot"></span> TicketFlow 官方票务平台
            </div>
            <h1 class="showcase-title">注册新账号</h1>
            <p class="showcase-subtitle">即刻加入 TicketFlow，开启无忧购票体验</p>

            <div class="feature-pills">
              <div class="feature-pill">
                <span class="icon">🎟️</span>
                <div class="txt">
                  <strong>海量热演票源</strong>
                  <span>覆盖全国数百城市展演</span>
                </div>
              </div>
              <div class="feature-pill">
                <span class="icon">⚡</span>
                <div class="txt">
                  <strong>极速出票服务</strong>
                  <span>下单即刻锁定理想席位</span>
                </div>
              </div>
              <div class="feature-pill">
                <span class="icon">🎁</span>
                <div class="txt">
                  <strong>新人专属礼包</strong>
                  <span>注册即享首单优惠提醒</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧注册表单面板 -->
        <div class="register-form-panel">
          <h2 class="form-title">会员注册</h2>
          <el-form ref="registerRef" :model="registerForm" :rules="registerRules" class="register-form" label-position="top">
            <el-form-item label="手机号码" prop="mobile">
              <el-input v-model="registerForm.mobile" placeholder="请输入11位手机号码" size="large" maxlength="11">
                <template #prepend>
                  <span class="region-code">中国 +86</span>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item label="设置密码" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                show-password
                placeholder="6-20位字母、数字或符号组合"
                size="large"
              />
            </el-form-item>

            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                show-password
                placeholder="请再次输入密码"
                size="large"
              />
            </el-form-item>

            <div class="terms-checkbox">
              <el-checkbox v-model="checkBox" @change="boxChange" />
              <span class="terms-text" :style="chkStyle">
                我已阅读并同意 <span class="link">《TicketFlow服务协议》</span> 与 <span class="link">《隐私政策》</span>
              </span>
            </div>

            <el-button
              size="large"
              type="primary"
              class="submit-btn"
              @click.prevent="handleAgreeLogin"
            >
              同意并注册
            </el-button>

            <div class="form-footer">
              <span>已有账号？</span>
              <router-link to="/login" class="login-link">立即登录 →</router-link>
            </div>
          </el-form>
        </div>
      </div>
    </div>

    <Verify
      mode="pop"
      :captchaType="captchaType"
      :imgSize="{ width: '400px', height: '200px' }"
      ref="verify"
      @update:value="handleValueFromChild"
    />

    <Footer></Footer>
  </div>
</template>

<script setup lang="ts">
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import Verify from '@/components/verifition/Verify.vue'
import { ref, onBeforeUnmount, getCurrentInstance } from 'vue'
import { isCaptchaApi, registerApi } from '@/api/login'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import $bus from '../utils/bus'

const { proxy } = getCurrentInstance()!
const router = useRouter()

const agreeOpt = ref<string>('我已阅读接受《TicketFlow会员服务协议》《隐私权政策》《订票服务条款》并同意自动注册成为会员')
const checkBox = ref<boolean>(false)
const chkStyle = ref<Record<string, string>>({})

interface RegisterForm {
  password: string
  confirmPassword: string
  captchaId: string
  mobile: string
  captchaVerification?: string
}

const registerForm = ref<RegisterForm>({
  password: '',
  confirmPassword: '',
  captchaId: '',
  mobile: ''
})

const validatePhone = (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
  const reg = /^1[3-9]\d{9}$/
  if (!value) {
    callback(new Error('手机号码不能为空'))
  } else if (!reg.test(value)) {
    callback(new Error('请输入正确的手机号码'))
  } else {
    callback()
  }
}

const equalToPassword = (_rule: unknown, value: string, callback: (err?: Error) => void): void => {
  if (registerForm.value.password !== value) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const registerRules = ref({
  mobile: [{ required: true, trigger: 'blur', validator: validatePhone }],
  password: [{
    required: true,
    pattern: /^(?![\d]+$)(?![a-zA-Z]+$)(?![^\da-zA-Z]+$)([^\u4e00-\u9fa5\s]){6,20}$/,
    message: '6-20位字母、数字或符号组合',
    trigger: ['blur', 'focus']
  }],
  confirmPassword: [
    { required: true, trigger: 'blur', message: '请再次输入您的密码' },
    { required: true, validator: equalToPassword, trigger: 'blur' }
  ],
})

function handleAgreeLogin() {
  isCaptchaApi().then((response: any) => {
    let { verifyCaptcha, captchaId } = response.data
    if (verifyCaptcha == false) {
      registerForm.value.captchaId = captchaId
      if (checkBox.value == false) {
        chkStyle.value = { color: '#EF4444' }
        ElMessage.warning('请勾选同意会员服务协议')
      } else {
        chkStyle.value = { color: 'var(--tf-text-secondary)' }
        registerInfo()
      }
    } else {
      if (checkBox.value == false) {
        chkStyle.value = { color: '#EF4444' }
        ElMessage.warning('请勾选同意会员服务协议')
      } else {
        chkStyle.value = { color: 'var(--tf-text-secondary)' }
        proxy.$refs.registerRef.validate((valid: boolean) => {
          if (valid) {
            onShow('blockPuzzle')
          }
        })
      }
    }
  })
}

function boxChange(val: any) {
  if (val == true) {
    chkStyle.value = { color: 'var(--tf-text-secondary)' }
  }
}

function registerInfo() {
  proxy.$refs.registerRef.validate((valid: boolean) => {
    if (valid) {
      registerApi(registerForm.value).then((response: any) => {
        if (response.code == '0') {
          ElMessage.success('注册成功')
          router.push({ name: 'Login' })
          reset()
        } else {
          ElMessage.error(response.message || '注册失败')
        }
      }).catch(() => {})
    }
  })
}

function reset() {
  registerForm.value = {
    password: '',
    confirmPassword: '',
    captchaId: '',
    mobile: ''
  }
}

const verify = ref<{ show: () => void } | null>(null)
const captchaType = ref<string>('')

const onShow = (type: string): void => {
  captchaType.value = type
  verify.value?.show()
}

const captchaVerify = ref<string>('')

const handleCaptchaRes = (data: any) => {
  captchaVerify.value = data.repData.captchaVerification
}
$bus.on('res', handleCaptchaRes)
onBeforeUnmount(() => $bus.off('res', handleCaptchaRes))

function handleValueFromChild(value: string) {
  if (value == '关闭') {
    registerForm.value.captchaVerification = captchaVerify.value
    isCaptchaApi().then((res: any) => {
      let { captchaId } = res.data
      registerForm.value.captchaId = captchaId
      registerApi(registerForm.value).then((response: any) => {
        if (response.code == '0' && response.data === true) {
          ElMessage.success('注册成功')
          router.push({ name: 'Login' })
          reset()
        } else {
          ElMessage.error(response.message || '注册验证失败')
        }
      }).catch(() => {})
    })
  }
}
</script>

<style scoped lang="scss">
.register-page {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;
}

.register-container {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px 20px;
}

.register-card {
  width: 960px;
  min-height: 580px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-lg);
  display: flex;
  overflow: hidden;
}

/* 左侧 Brand Showcase 面板 */
.register-showcase {
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

/* 右侧注册表单面板 */
.register-form-panel {
  width: 460px;
  padding: 40px 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;

  .form-title {
    font-size: 22px;
    font-weight: 700;
    color: var(--tf-text-primary);
    margin: 0 0 24px 0;
  }

  .region-code {
    font-size: 13px;
    color: var(--tf-text-secondary);
    font-weight: 500;
  }

  .terms-checkbox {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin: 12px 0 20px;
    font-size: 12px;
    line-height: 1.5;

    .terms-text {
      color: var(--tf-text-secondary);

      .link {
        color: var(--tf-primary);
        cursor: pointer;
      }
    }
  }

  .submit-btn {
    width: 100%;
    height: 46px;
    font-size: 16px;
    font-weight: 600;
    border-radius: var(--tf-radius-full);
  }

  .form-footer {
    display: flex;
    justify-content: center;
    gap: 6px;
    font-size: 14px;
    color: var(--tf-text-secondary);
    margin-top: 20px;

    .login-link {
      color: var(--tf-primary);
      font-weight: 600;
      text-decoration: none;
      &:hover { text-decoration: underline; }
    }
  }
}
</style>
