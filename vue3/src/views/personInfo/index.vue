<template>
  <!--个人信息-->
  <Header></Header>
  <div class="red-line"></div>
  <div class="section">
    <MenuSideBar class="sidebarMenu" activeIndex="3"></MenuSideBar>
    <div class="right-section">
      <!-- 2026 个人中心 User Hero Card -->
      <div class="user-hero-card">
        <div class="user-hero-avatar">
          <img src="@/assets/login/photo.png" alt="Avatar" />
        </div>
        <div class="user-hero-info">
          <h2 class="user-hero-name">{{ perInfoForm.name || '尊贵会员' }}</h2>
          <p class="user-hero-sub">TicketFlow 官方认证会员 · 享受优先极速抢票权益</p>
        </div>
        <div class="user-hero-tags">
          <span class="hero-tag">🎟️ 实名买家</span>
          <span class="hero-tag hero-tag--primary">VIP 会员</span>
        </div>
      </div>

      <div class="page-card">
        <div class="page-card__header">
          <span class="page-card__title">个人信息</span>
          <span class="page-card__tip">完善更多个人信息，有助于我们为您提供更加个性化的服务</span>
        </div>
        <div class="page-card__body">
          <el-form ref="perInfoRef" :model="perInfoForm" :rules="perInfoRules" label-width="100px" class="perInfo-form">
            <el-form-item label="昵称" prop="name">
              <el-input v-model="perInfoForm.name" placeholder="请输入昵称" />
            </el-form-item>
            <el-form-item label="真实姓名" prop="relName">
              <el-input v-model="perInfoForm.relName" placeholder="请输入真实姓名" />
            </el-form-item>
            <el-form-item label="性别" prop="gender">
              <el-radio-group v-model="perInfoForm.gender">
                <el-radio value="1">男</el-radio>
                <el-radio value="2">女</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="身份证号" prop="idNumber">
              <el-input v-model="perInfoForm.idNumber" placeholder="请输入身份证号" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click.prevent="getPersonList" style="min-width:120px">保存信息</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </div>
  </div>
  <Footer class="foot"></Footer>
</template>

<script setup lang="ts">
import MenuSideBar from '@/components/menuSidebar/index'
import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import { ref, reactive, getCurrentInstance, nextTick, onMounted } from 'vue'
import { getPersonInfo, getPersonInfoId } from '@/api/personInfo'
import { useAuthStore } from '@/store/modules/auth'
import { ElMessage } from 'element-plus'
import { getUserIdKey } from '@/utils/auth'

interface PersonInfoForm {
  name: string
  relName: string
  gender: string
  idNumber: string
  id: string | undefined
}

const { proxy } = getCurrentInstance()!
const useUser = useAuthStore()

const perInfoForm = reactive<PersonInfoForm>({
  name: '',
  relName: '',
  gender: '1',
  idNumber: '',
  id: useUser.userId,
})

const perInfoRules = ref({
  name: [{ required: true, trigger: 'blur', message: '请输入昵称' }],
  gender: [{ required: true, trigger: 'blur' }],
})

function getPersonList(): void {
  ;(proxy as any).$refs.perInfoRef.validate((valid: boolean) => {
    if (valid) {
      getPersonInfo(perInfoForm).then((response: any) => {
        if (response.code == 0) {
          ElMessage({ message: '保存成功', type: 'success' })
        } else {
          ElMessage({ message: response.message, type: 'error' })
        }
      })
    }
  })
}

onMounted(() => {
  nextTick(() => {
    getPersonInfoIdList()
  })
})

async function getPersonInfoIdList(): Promise<void> {
  const id = getUserIdKey()
  getPersonInfoId({ id }).then((response: any) => {
    const { gender, id: userId, idNumber, name, relName } = response.data
    perInfoForm.name = name
    perInfoForm.relName = relName
    perInfoForm.gender = gender
    perInfoForm.idNumber = idNumber
    perInfoForm.id = userId
  }).catch(() => {})
}
</script>

<style scoped lang="scss">
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

.user-hero-card {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 24px 28px;
  background: var(--tf-surface);
  border: 1px solid var(--tf-border);
  border-radius: var(--tf-radius-lg);
  margin-bottom: 20px;
  box-shadow: var(--tf-shadow-sm);

  .user-hero-avatar img {
    width: 60px;
    height: 60px;
    border-radius: 50%;
    border: 2px solid var(--tf-surface);
    box-shadow: var(--tf-shadow-md);
  }

  .user-hero-info {
    flex: 1;

    .user-hero-name {
      margin: 0 0 4px;
      font-size: 20px;
      font-weight: 700;
      color: var(--tf-text-primary);
    }

    .user-hero-sub {
      margin: 0;
      font-size: 13px;
      color: var(--tf-text-secondary);
    }
  }

  .user-hero-tags {
    display: flex;
    gap: 8px;

    .hero-tag {
      padding: 4px 12px;
      font-size: 12px;
      font-weight: 600;
      border-radius: var(--tf-radius-full);
      background: var(--tf-bg);
      border: 1px solid var(--tf-border);
      color: var(--tf-text-secondary);

      &--primary {
        background: var(--tf-primary-light);
        color: var(--tf-primary);
        border-color: transparent;
      }
    }
  }
}

.page-card {
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-sm);
  overflow: hidden;

  &__header {
    padding: 20px 28px 18px;
    border-bottom: 1px solid var(--tf-border);
  }

  &__title {
    font-size: 17px;
    font-weight: 700;
    color: var(--tf-text-primary);
    display: block;
    margin-bottom: 4px;
  }

  &__tip {
    font-size: 12px;
    color: var(--tf-text-secondary);
  }

  &__body {
    padding: 32px 28px;
  }
}

.perInfo-form {
  max-width: 480px;
  margin-top: 8px;
}

.foot {
  margin-top: 80px;
}
</style>
