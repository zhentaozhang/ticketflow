<template>
  <div class="container">
    <Header></Header>
    <div class="red-line"></div>
    <div class="section">
      <MenuSideBar class="sidebarMenu" activeIndex="4"></MenuSideBar>
      <div class="right-section">

        <!-- User Hero Card (从 personInfo 统一复用) -->
        <div class="section-header">
          <div class="section-header__left">
            <h2 class="section-title">常用购票人</h2>
            <span class="section-sub">管理您的常用观演人信息，购票时快速选择</span>
          </div>
          <el-button type="primary" @click="addTicketUser" :icon="Plus">新增购票人</el-button>
        </div>

        <!-- 购票人 Card Grid -->
        <div class="ticket-user-grid" v-if="isShow && ticketUserListData.length">
          <div class="user-card" v-for="item in ticketUserListData" :key="item.id">
            <div class="user-card__avatar">
              <el-icon :size="24"><User /></el-icon>
            </div>
            <div class="user-card__info">
              <div class="user-card__name">{{ item.relName }}</div>
              <div class="user-card__id-type">
                <el-tag size="small" round>{{ getIdTypeName(item.idType) }}</el-tag>
              </div>
              <div class="user-card__id-num">{{ item.idNumber }}</div>
            </div>
            <button class="user-card__del" @click="delTicketUser(item.id)">
              <el-icon><Delete /></el-icon>
            </button>
          </div>
        </div>

        <!-- 空状态 -->
        <div class="empty-state" v-if="isShow && !ticketUserListData.length">
          <el-empty description="暂无购票人，点击右上角新增" />
        </div>

        <!-- 新增购票人表单 Panel -->
        <div class="add-form-panel" v-if="!isShow">
          <div class="add-form-panel__header">
            <span class="add-form-panel__title">新增购票人信息</span>
            <button class="add-form-panel__close" @click="closeTicket">
              <el-icon><Close /></el-icon>
            </button>
          </div>
          <el-form
            ref="ticketRef"
            :model="formTicket"
            :rules="formTicketRules"
            label-width="96px"
            class="ticket-form"
          >
            <el-form-item label="真实姓名" prop="relName">
              <el-input v-model="formTicket.relName" placeholder="请填写真实姓名" />
            </el-form-item>
            <el-form-item label="证件类型" prop="idType">
              <el-select v-model="formTicket.idType" style="width: 240px;">
                <el-option
                  v-for="item in idType"
                  :key="item.value"
                  :value="item.value"
                  :label="item.name"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="证件号码" prop="idNumber">
              <el-input v-model="formTicket.idNumber" placeholder="请填写证件号码" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click.prevent="saveTicket">保存</el-button>
              <el-button @click.prevent="closeTicket">取消</el-button>
            </el-form-item>
          </el-form>
        </div>

      </div>
    </div>
    <Footer class="foot"></Footer>
  </div>
</template>

<script setup lang="ts" name="TicketUser">
import MenuSideBar from '@/components/menuSidebar/index'
import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import { ref, reactive, getCurrentInstance } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { delTicketUserApi, selectTicketUserListApi } from '@/api/accountCenter'
import { getIdTypeName } from '@/utils/idType'
import { ElMessage } from 'element-plus'
import { getUserIdKey } from '@/utils/auth'
import { saveTicketUser } from '@/api/buyTicketUser'
import { ID_TYPE } from '@/utils/constants'
import { User, Delete, Close, Plus } from '@element-plus/icons-vue'

interface TicketForm {
  relName?: string
  idType: string
  idNumber?: string
  userId?: string | undefined
}

const { proxy } = getCurrentInstance()!
const useUser = useAuthStore()

const ticketUserListParams = reactive({ userId: useUser.userId })
const formTicket = ref<TicketForm>({ idType: '1' })

const formTicketRules = ref({
  relName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  idNumber: [{ required: true, message: '请输入证件号码', trigger: 'blur' }],
})

const ticketUserListData = ref<any[]>([])
const isShow = ref<boolean>(true)
const idType = ref(ID_TYPE)

selectTicketUserList()

function selectTicketUserList(): void {
  selectTicketUserListApi(ticketUserListParams).then((response: any) => {
    ticketUserListData.value = response.data || []
  })
}

function delTicketUser(ticketUserId: string): void {
  delTicketUserApi({ id: ticketUserId }).then(() => {
    ElMessage.success('删除成功')
    selectTicketUserList()
  })
}

function addTicketUser(): void {
  isShow.value = false
  reset()
}

function saveTicket(): void {
  ;(proxy as any).$refs.ticketRef.validate((valid: boolean) => {
    if (valid) {
      formTicket.value.userId = getUserIdKey()
      saveTicketUser(formTicket.value).then((response: any) => {
        if (response.code == 0) {
          ElMessage.success('保存成功')
          isShow.value = true
          selectTicketUserList()
          reset()
        }
      })
    }
  })
}

function closeTicket(): void {
  isShow.value = true
  reset()
}

function reset(): void {
  formTicket.value = { idType: '1' }
}
</script>

<style scoped lang="scss">
.container {
  min-height: 100vh;
  background: var(--tf-bg);
  display: flex;
  flex-direction: column;
}

.red-line {
  border-bottom: 4px solid var(--tf-primary);
}

.section {
  width: 1100px;
  margin: 24px auto 0;
  display: flex;
  gap: 20px;
  align-items: flex-start;

  .sidebarMenu { flex-shrink: 0; }

  .right-section {
    flex: 1;
    min-width: 0;
  }
}

.section-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 24px;

  &__left {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .section-title {
    margin: 0;
    font-size: 20px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  .section-sub {
    font-size: 13px;
    color: var(--tf-text-secondary);
  }
}

/* 购票人 Card Grid */
.ticket-user-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.user-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 20px;
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  box-shadow: var(--tf-shadow-sm);
  position: relative;
  transition: all 0.2s;

  &:hover {
    box-shadow: var(--tf-shadow-md);
    border-color: var(--tf-primary);
  }

  &__avatar {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    background: var(--tf-primary-light);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--tf-primary);
    flex-shrink: 0;
  }

  &__info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  &__name {
    font-size: 16px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  &__id-num {
    font-size: 12px;
    color: var(--tf-text-muted);
    font-family: monospace;
    letter-spacing: 0.5px;
  }

  &__del {
    position: absolute;
    top: 12px;
    right: 12px;
    background: transparent;
    border: none;
    color: var(--tf-text-muted);
    cursor: pointer;
    padding: 4px;
    border-radius: 6px;
    display: flex;
    align-items: center;
    transition: all 0.2s;

    &:hover {
      color: var(--tf-danger);
      background: rgba(239, 68, 68, 0.1);
    }
  }
}

/* 空状态 */
.empty-state {
  padding: 60px 0;
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  text-align: center;
}

/* 新增表单 Panel */
.add-form-panel {
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  box-shadow: var(--tf-shadow-sm);
  overflow: hidden;

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 24px;
    border-bottom: 1px solid var(--tf-border);
    background: var(--tf-bg);
  }

  &__title {
    font-size: 16px;
    font-weight: 700;
    color: var(--tf-text-primary);
  }

  &__close {
    background: transparent;
    border: none;
    color: var(--tf-text-secondary);
    cursor: pointer;
    padding: 4px;
    display: flex;
    align-items: center;
    border-radius: 6px;
    transition: all 0.2s;

    &:hover {
      color: var(--tf-text-primary);
      background: var(--tf-bg);
    }
  }
}

.ticket-form {
  padding: 28px 24px;
  max-width: 480px;
}

.foot {
  margin-top: 60px;
}
</style>
