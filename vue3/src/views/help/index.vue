<template>
  <div class="help-page">
    <Header></Header>

    <div class="help-container">
      <!-- 顶部 Banner 区域 -->
      <div class="help-hero">
        <div class="help-hero__content">
          <h1 class="help-hero__title">TicketFlow 帮助与服务中心</h1>
          <p class="help-hero__sub">随时为您解答购票、退换、服务条款及公司资讯相关疑问</p>
        </div>
      </div>

      <!-- 主体内容区：左侧菜单 + 右侧内容 -->
      <div class="help-main">
        <!-- 左侧导航 -->
        <div class="help-sidebar">
          <div class="menu-group" v-for="group in menuGroups" :key="group.title">
            <div class="menu-group__title">{{ group.title }}</div>
            <div
              v-for="item in group.items"
              :key="item.key"
              class="menu-item"
              :class="{ 'menu-item--active': activeTab === item.key }"
              @click="switchTab(item.key)"
            >
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </div>
          </div>
        </div>

        <!-- 右侧内容卡片区 -->
        <div class="help-content">

          <!-- 1. 常见问题 FAQ -->
          <div v-if="activeTab === 'faq'" class="content-panel">
            <h2 class="panel-title">常见问题 (FAQ)</h2>
            <div class="faq-search">
              <el-input
                v-model="faqSearchKey"
                placeholder="搜索您遇到的问题..."
                :prefix-icon="Search"
                clearable
                size="large"
              />
            </div>
            <el-collapse v-model="activeFaq" accordion class="faq-collapse">
              <el-collapse-item
                v-for="(item, idx) in filteredFaqs"
                :key="idx"
                :title="item.q"
                :name="idx"
              >
                <div class="faq-answer">{{ item.a }}</div>
              </el-collapse-item>
            </el-collapse>
          </div>

          <!-- 2. 如何购票 -->
          <div v-if="activeTab === 'buy-guide'" class="content-panel">
            <h2 class="panel-title">如何购票 (购票指南)</h2>
            <p class="panel-desc">只需 4 步，轻松开启您的美好演出之旅：</p>

            <div class="guide-steps">
              <div class="step-card" v-for="(step, i) in guideSteps" :key="i">
                <div class="step-card__num">0{{ i + 1 }}</div>
                <div class="step-card__info">
                  <h3 class="step-card__title">{{ step.title }}</h3>
                  <p class="step-card__desc">{{ step.desc }}</p>
                </div>
              </div>
            </div>

            <div class="guide-tips">
              <h4>💡 购票小贴士</h4>
              <ul>
                <li>热销演出建议提前登录并绑定实名观演人信息，以便抢票时快速提交。</li>
                <li>同一订单限购 6 张，实名制项目每张票需对应一位观演人证件。</li>
              </ul>
            </div>
          </div>

          <!-- 3. 退换说明 -->
          <div v-if="activeTab === 'refund-policy'" class="content-panel">
            <h2 class="panel-title">退换票服务说明</h2>
            <div class="policy-alert">
              <el-icon><WarningFilled /></el-icon>
              <span>鉴于票品为特殊价票券，其背后承载的文化服务具有时效性、稀缺性等特征，订购成功后原则上不支持退换。</span>
            </div>

            <div class="policy-sections">
              <div class="policy-item">
                <h3>一、不可退票情形</h3>
                <p>因个人原因（如行程变更、生病、误车等）无法到场观演的，演出票品不支持退票及换票。</p>
              </div>
              <div class="policy-item">
                <h3>二、不可抗力与主办方变更</h3>
                <p>若因不可抗力（重大灾害、政策调整等）或主办方原因导致演出延期或取消，平台将自动为您办理全额退款，资金将原路退回至您的支付账户，无需手动申请。</p>
              </div>
              <div class="policy-item">
                <h3>三、退款到账时间</h3>
                <p>退款申请通过后，资金将在 1-3 个工作日内退回您的原支付渠道。</p>
              </div>
            </div>
          </div>

          <!-- 4. 公司介绍 -->
          <div v-if="activeTab === 'about'" class="content-panel">
            <h2 class="panel-title">关于 TicketFlow</h2>
            <p class="panel-desc">TicketFlow 是面向下一代的新型现场娱乐票务与体验平台。</p>

            <div class="about-grid">
              <div class="about-card">
                <div class="about-card__icon">🎭</div>
                <h3>海量现场</h3>
                <p>覆盖演唱会、话剧歌剧、体育赛事、音乐会、二次元展览等全品类娱乐体验。</p>
              </div>
              <div class="about-card">
                <div class="about-card__icon">⚡</div>
                <h3>极速出票</h3>
                <p>自研高性能出票引擎，支持百万人次同时在线抢票与电子票秒级验票入场。</p>
              </div>
              <div class="about-card">
                <div class="about-card__icon">🛡️</div>
                <h3>100% 真票保障</h3>
                <p>官方直营与一级代理对接，全程防伪溯源，坚决杜绝假票风险。</p>
              </div>
            </div>
          </div>

          <!-- 5. 联系我们 -->
          <div v-if="activeTab === 'contact'" class="content-panel">
            <h2 class="panel-title">联系我们</h2>
            <p class="panel-desc">感谢使用 TicketFlow！欢迎通过 GitHub 与我们联系与交流：</p>
            <div class="contact-real-box">
              <div class="contact-real-card">
                <el-icon :size="32" class="c-icon"><Share /></el-icon>
                <div class="contact-real-info">
                  <div class="contact-real-title">GitHub 开发者主页</div>
                  <a href="https://github.com/zhentaozhang/" target="_blank" rel="noopener noreferrer" class="contact-real-url">
                    https://github.com/zhentaozhang/
                  </a>
                </div>
              </div>
            </div>
          </div>

          <!-- 6. 加入我们 -->
          <div v-if="activeTab === 'join'" class="content-panel">
            <h2 class="panel-title">加入我们</h2>
            <div class="join-real-box">
              <div class="join-banner">
                <h3>🤝 寻找志同道合的朋友</h3>
                <p>我们热爱探讨优秀的软件架构与高品质的前端交互体验。如果您对 TicketFlow 项目感兴趣，欢迎共同探讨、提交 Issue 或提交 Pull Request，一起把项目打造得更完善！</p>
              </div>
              <div class="join-actions">
                <a href="https://github.com/zhentaozhang/" target="_blank" rel="noopener noreferrer" class="join-link">
                  <el-button type="primary" size="large">访问 GitHub 参与交流</el-button>
                </a>
              </div>
            </div>
          </div>

          <!-- 7. 用户协议 -->
          <div v-if="activeTab === 'user-agreement'" class="content-panel">
            <h2 class="panel-title">用户服务协议</h2>
            <div class="legal-doc">
              <p><strong>版本更新日期：2026年01月01日</strong></p>
              <p>欢迎使用 TicketFlow 平台！在您注册成为 TicketFlow 用户前，请务必审慎阅读、充分理解各条款内容。本协议系由您与 TicketFlow 平台就平台服务所订立的契约。</p>
              <h3>一、服务内容及规范</h3>
              <p>1.1 TicketFlow 为用户提供在线票务信息浏览、门票购买、电子票查验等服务。</p>
              <p>1.2 用户在订购票品时，应提供真实有效的身份信息与联系方式。</p>
              <h3>二、用户账号与安全</h3>
              <p>2.1 您有责任妥善保管您的注册账号和密码，并对该账号下发生的所有活动承担责任。</p>
            </div>
          </div>

          <!-- 8. 隐私政策 -->
          <div v-if="activeTab === 'privacy-policy'" class="content-panel">
            <h2 class="panel-title">隐私政策与数据保护</h2>
            <div class="legal-doc">
              <p><strong>版本更新日期：2026年01月01日</strong></p>
              <p>TicketFlow（以下简称“我们”）非常重视用户的隐私和个人信息保护。本隐私政策将帮助您了解我们如何收集、使用、存储及保护您的个人信息。</p>
              <h3>一、我们如何收集您的信息</h3>
              <p>1.1 当您注册账号时，我们会收集您的手机号码或邮箱地址。</p>
              <p>1.2 当您订购门票时，因实名制入场要求，我们需收集观演人的真实姓名与身份证件号码。</p>
              <h3>二、数据安全保障</h3>
              <p>我们采用行业领先的数据加密传输（TLS 1.3）与落盘加密技术，严格保障您的个人信息安全。</p>
            </div>
          </div>

        </div>
      </div>
    </div>

    <Footer class="foot"></Footer>
  </div>
</template>

<script setup lang="ts">
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  HelpFilled,
  Tickets,
  RefreshLeft,
  InfoFilled,
  PhoneFilled,
  UserFilled,
  Document,
  Lock,
  Search,
  WarningFilled,
  Share,
  Link,
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref<string>('faq')
const faqSearchKey = ref<string>('')
const activeFaq = ref<number | null>(0)

// 导航菜单配置
const menuGroups = [
  {
    title: '帮助中心',
    items: [
      { key: 'faq', label: '常见问题', icon: HelpFilled },
      { key: 'buy-guide', label: '如何购票', icon: Tickets },
      { key: 'refund-policy', label: '退换说明', icon: RefreshLeft },
    ],
  },
  {
    title: '关于我们',
    items: [
      { key: 'about', label: '公司介绍', icon: InfoFilled },
      { key: 'contact', label: '联系我们', icon: PhoneFilled },
      { key: 'join', label: '加入我们', icon: UserFilled },
    ],
  },
  {
    title: '服务条款',
    items: [
      { key: 'user-agreement', label: '用户协议', icon: Document },
      { key: 'privacy-policy', label: '隐私政策', icon: Lock },
    ],
  },
]

// 监听路由参数 ?tab=xxx 动态切换
watch(
  () => route.query.tab,
  (val) => {
    if (val && typeof val === 'string') {
      activeTab.value = val
    }
  },
  { immediate: true }
)

function switchTab(key: string) {
  activeTab.value = key
  router.push({ path: '/help', query: { tab: key } })
  window.scrollTo({ top: 180, behavior: 'smooth' })
}

// 常见问题数据
const faqs = [
  { q: '订票成功后，什么时候可以拿到门票？', a: '对于电子票，支付成功后即可前往【个人中心 - 我的票夹】查看电子入场二维码；若为纸质票，我们将在演出开始前 3-5 天通过顺丰快递寄出。' },
  { q: '可以帮朋友购买门票吗？', a: '可以。购票时只需在【实名观演人】列表中勾选您朋友的信息即可。实名制演出请确保填写的证件号与入场持证人一致。' },
  { q: '支付超时怎么办？', a: '订单生成后请在 15 分钟内完成支付。若超时未支付，系统将自动释放锁定锁座，您可以重新挑选票档并提交订单。' },
  { q: '演出取消或延期如何退票？', a: '若演出延期或取消，平台会在接到主办方通知后第一时间短信通知您，并开启原路全额自动退款流程，无需您手动操作。' },
]

const filteredFaqs = computed(() => {
  if (!faqSearchKey.value.trim()) return faqs
  const k = faqSearchKey.value.trim().toLowerCase()
  return faqs.filter(item => item.q.toLowerCase().includes(k) || item.a.toLowerCase().includes(k))
})

// 购票步骤
const guideSteps = [
  { title: '搜索挑选演出', desc: '在首页或分类搜索您喜爱的演唱会、话剧、体育赛事等节目。' },
  { title: '选择场次票档', desc: '选择合适的演出现场日期、时间及心仪的票价档位，支持自主选座。' },
  { title: '确认实名信息', desc: '选择或添加观演人身份信息，核对无误后提交订单。' },
  { title: '完成快捷支付', desc: '使用支付宝等在线支付工具完成支付，即可在票夹中生成门票。' },
]
</script>

<style scoped lang="scss">
.help-page {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;
}

.help-container {
  max-width: var(--tf-max-width, 1200px);
  width: 100%;
  margin: 0 auto;
  padding: 0 20px;
  box-sizing: border-box;
  flex: 1;
}

/* Hero 头部 Banner */
.help-hero {
  margin: 20px 0 28px;
  padding: 40px 32px;
  background: linear-gradient(135deg, var(--tf-primary) 0%, #FF6B57 100%);
  border-radius: var(--tf-radius-lg);
  color: #ffffff;
  box-shadow: 0 8px 24px rgba(255, 55, 29, 0.2);

  &__title {
    font-size: 26px;
    font-weight: 700;
    margin: 0 0 8px 0;
  }

  &__sub {
    font-size: 14px;
    opacity: 0.9;
    margin: 0;
  }
}

/* 主体 Grid */
.help-main {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

/* 左侧菜单 */
.help-sidebar {
  width: 240px;
  flex-shrink: 0;
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  padding: 16px;
  box-shadow: var(--tf-shadow-sm);
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.menu-group {
  &__title {
    font-size: 12px;
    font-weight: 700;
    color: var(--tf-text-muted);
    text-transform: uppercase;
    letter-spacing: 0.5px;
    padding: 0 12px 8px;
  }

  .menu-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    border-radius: var(--tf-radius-md);
    font-size: 14px;
    color: var(--tf-text-secondary);
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
      color: var(--tf-primary);
      background: var(--tf-bg);
    }

    &--active {
      color: var(--tf-primary);
      background: var(--tf-primary-light);
      font-weight: 600;
    }
  }
}

/* 右侧内容面板 */
.help-content {
  flex: 1;
  min-width: 0;
}

.content-panel {
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  padding: 32px;
  box-shadow: var(--tf-shadow-sm);

  .panel-title {
    font-size: 20px;
    font-weight: 700;
    color: var(--tf-text-primary);
    margin: 0 0 8px 0;
  }

  .panel-desc {
    font-size: 14px;
    color: var(--tf-text-secondary);
    margin: 0 0 24px 0;
  }
}

/* FAQ 手风琴 */
.faq-search {
  margin-bottom: 20px;
}

.faq-collapse {
  border: none;

  :deep(.el-collapse-item__header) {
    font-size: 15px;
    font-weight: 600;
    color: var(--tf-text-primary);
    background: transparent;
    border-bottom-color: var(--tf-border);
    padding: 14px 0;
  }

  :deep(.el-collapse-item__wrap) {
    background: transparent;
    border-bottom-color: var(--tf-border);
  }

  .faq-answer {
    font-size: 14px;
    color: var(--tf-text-secondary);
    line-height: 1.7;
    padding: 8px 0 16px;
  }
}

/* 步骤指南 */
.guide-steps {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 32px;

  .step-card {
    display: flex;
    gap: 16px;
    padding: 20px;
    border: 1px solid var(--tf-border);
    border-radius: var(--tf-radius-md);
    background: var(--tf-bg);

    &__num {
      font-size: 28px;
      font-weight: 800;
      color: var(--tf-primary);
      font-family: monospace;
    }

    &__title {
      font-size: 16px;
      font-weight: 600;
      color: var(--tf-text-primary);
      margin: 0 0 6px 0;
    }

    &__desc {
      font-size: 13px;
      color: var(--tf-text-secondary);
      margin: 0;
      line-height: 1.5;
    }
  }
}

.guide-tips {
  padding: 16px 20px;
  background: var(--tf-primary-light);
  border-radius: var(--tf-radius-md);

  h4 {
    margin: 0 0 8px 0;
    font-size: 14px;
    color: var(--tf-primary);
  }

  ul {
    margin: 0;
    padding-left: 18px;
    font-size: 13px;
    color: var(--tf-text-secondary);
    line-height: 1.6;
  }
}

/* 退换说明 */
.policy-alert {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  background: rgba(234, 179, 8, 0.1);
  border: 1px solid rgba(234, 179, 8, 0.3);
  border-radius: var(--tf-radius-md);
  color: var(--tf-text-primary);
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 24px;
}

.policy-sections {
  display: flex;
  flex-direction: column;
  gap: 20px;

  .policy-item {
    h3 {
      font-size: 15px;
      font-weight: 600;
      color: var(--tf-text-primary);
      margin: 0 0 8px 0;
    }

    p {
      font-size: 14px;
      color: var(--tf-text-secondary);
      line-height: 1.7;
      margin: 0;
    }
  }
}

/* 关于我们 */
.about-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;

  .about-card {
    padding: 24px;
    border: 1px solid var(--tf-border);
    border-radius: var(--tf-radius-md);
    background: var(--tf-bg);
    text-align: center;

    &__icon {
      font-size: 32px;
      margin-bottom: 12px;
    }

    h3 {
      font-size: 16px;
      font-weight: 600;
      color: var(--tf-text-primary);
      margin: 0 0 8px 0;
    }

    p {
      font-size: 13px;
      color: var(--tf-text-secondary);
      line-height: 1.6;
      margin: 0;
    }
  }
}

/* 联系我们真实卡片 */
.contact-real-box {
  margin-top: 16px;
}

.contact-real-card {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 24px 28px;
  background: var(--tf-bg);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-md);

  .c-icon {
    color: var(--tf-primary);
    flex-shrink: 0;
  }

  .contact-real-info {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .contact-real-title {
    font-size: 16px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  .contact-real-url {
    font-size: 15px;
    color: var(--tf-primary);
    text-decoration: none;
    font-weight: 600;
    word-break: break-all;

    &:hover {
      text-decoration: underline;
    }
  }
}

/* 加入我们真实卡片 */
.join-real-box {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.join-banner {
  padding: 28px;
  background: var(--tf-bg);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-md);

  h3 {
    margin: 0 0 12px 0;
    font-size: 18px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  p {
    margin: 0;
    font-size: 14px;
    color: var(--tf-text-secondary);
    line-height: 1.7;
  }
}

.join-actions {
  .join-link {
    text-decoration: none;
  }
}

/* 法律正文 */
.legal-doc {
  font-size: 14px;
  color: var(--tf-text-secondary);
  line-height: 1.8;

  h3 {
    font-size: 16px;
    font-weight: 600;
    color: var(--tf-text-primary);
    margin: 20px 0 10px 0;
  }
}

.foot {
  margin-top: 60px;
}
</style>
