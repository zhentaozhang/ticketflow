<template>
  <div class="accountSettings">
    <Header></Header>
    <div class="section">
      <MenuSideBar class="sidebarMenu" activeIndex="2"></MenuSideBar>
      <div class="right-section">
        <div class="page-card">
          <div class="page-card__header">
            <span class="page-card__title">账号设置</span>
          </div>
          <div class="page-card__body">
            <div class="settings-list">
              <div class="setting-item" v-for="item in accountLists" :key="item.nameInfo">
                <div class="setting-item__main">
                  <div class="setting-item__name">
                    <el-icon v-if="item.nameInfoStyle === 'name-info-yes'" class="icon-success"><CircleCheckFilled /></el-icon>
                    <el-icon v-else class="icon-warning"><WarningFilled /></el-icon>
                    {{ item.nameInfo }}
                  </div>
                  <div class="setting-item__desc">{{ item.detailInfo }}</div>
                </div>
                <div class="setting-item__action">
                  <template v-if="experienceAccountFlag != 1">
                    <router-link :to="item.path">
                      <el-button :type="item.nameInfoStyle === 'name-info-yes' ? 'default' : 'primary'" plain size="small">
                        {{ item.explainInfo }}
                      </el-button>
                    </router-link>
                  </template>
                  <el-tag v-else type="info" size="small">体验不支持</el-tag>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <Footer class="foot"></Footer>
  </div>
</template>

<script setup lang="ts">
import MenuSideBar from '@/components/menuSidebar/index'
import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import { useAuthStore } from '@/store/modules/auth'
import { getUserIdKey } from '@/utils/auth'
import { getPersonInfoId } from '@/api/personInfo'
import { ref, reactive } from 'vue'
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'

interface AccountItem {
  nameInfo: string
  detailInfo: string
  explainInfo: string
  path: string
  nameInfoStyle: string
}

const experienceAccountFlag = ref<string>(import.meta.env.VITE_EXPERIENCE_ACCOUNT_FLAG)
const accountLists = ref<AccountItem[]>([])
const telNum = ref<string>('')

const accountList = reactive<AccountItem[]>([
  {
    nameInfo: '登录密码',
    detailInfo: '',
    explainInfo: '修改',
    path: './editPassword',
    nameInfoStyle: 'name-info-yes',
  },
  {
    nameInfo: '邮箱验证',
    detailInfo: '验证邮箱可帮助您快速找回密码，并可接收订单、演出通知、促销活动等提醒',
    explainInfo: '立即绑定',
    path: './email',
    nameInfoStyle: 'name-info-yes',
  },
  {
    nameInfo: '手机验证',
    detailInfo: `您验证的手机：${telNum.value}`,
    explainInfo: '更换',
    path: './mobile',
    nameInfoStyle: 'name-info-yes',
  },
  {
    nameInfo: '实名认证',
    detailInfo: '认证您的实名信息，提高安全等级',
    explainInfo: '立即验证',
    path: './authentication',
    nameInfoStyle: 'name-info-yes',
  },
])

getIsVaild()

function getIsVaild(): void {
  const id = getUserIdKey()
  getPersonInfoId({ id }).then((response: any) => {
    const { relAuthenticationStatus, emailStatus, mobile } = response.data
    telNum.value = mobile
    accountList[2].detailInfo = `您验证的手机：${mobile}`
    accountLists.value = accountList.map(item => {
      if (item.nameInfo === '邮箱验证') {
        item.nameInfoStyle = emailStatus === '0' ? 'name-info-no' : 'name-info-yes'
      } else if (item.nameInfo === '实名认证') {
        item.nameInfoStyle = relAuthenticationStatus === '0' ? 'name-info-no' : 'name-info-yes'
      }
      return item
    })
  }).catch(() => {})
}
</script>

<style scoped lang="scss">
.accountSettings {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.section {
  width: 1100px;
  margin: 24px auto 0;
  display: flex;
  gap: 20px;
  align-items: flex-start;
  flex: 1;

  .sidebarMenu { flex-shrink: 0; }

  .right-section {
    flex: 1;
    min-width: 0;
  }
}

.page-card {
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-sm);
  overflow: hidden;

  &__header {
    padding: 20px 28px;
    border-bottom: 1px solid var(--tf-border);
  }

  &__title {
    font-size: 17px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  &__body {
    padding: 12px 28px 32px;
  }
}

.settings-list {
  display: flex;
  flex-direction: column;
}

.setting-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px 0;
  border-bottom: 1px solid var(--tf-border);

  &:last-child {
    border-bottom: none;
  }

  &__main {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__name {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
    font-weight: 600;
    color: var(--tf-text-primary);

    .icon-success {
      color: var(--tf-success);
      font-size: 18px;
    }
    
    .icon-warning {
      color: var(--tf-warning);
      font-size: 18px;
    }
  }

  &__desc {
    font-size: 13px;
    color: var(--tf-text-secondary);
    padding-left: 26px; /* align with text after icon */
  }

  &__action {
    flex-shrink: 0;
    margin-left: 20px;
  }
}

.foot {
  margin-top: 60px;
}
</style>
