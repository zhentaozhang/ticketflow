<template>
  <Header></Header>
  <div class="buy-ticket-user-page">
    <div class="buy-card">
      <div class="buy-card__header">
        <h2 class="buy-card__title">添加观演人</h2>
        <span class="buy-card__tip">请填写观演人的真实身份信息</span>
      </div>
      <div class="buy-card__body">
        <el-form ref="formTicketRef" :model="form" :rules="rules" label-position="top">
          <el-form-item label="真实姓名" prop="relName">
            <el-input v-model="form.relName" placeholder="请填写观演人姓名" />
          </el-form-item>
          <el-form-item label="证件类型" prop="idType">
            <el-select v-model="form.idType" style="width: 100%;">
              <el-option
                v-for="item in idType"
                :key="item.value"
                :value="item.value"
                :label="item.name"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="证件号码" prop="idNumber">
            <el-input v-model="form.idNumber" placeholder="请填写证件号码" />
          </el-form-item>

          <div class="agreement-tip">
            <el-icon class="icon"><Warning /></el-icon>
            点击确定表示您已阅读并同意 <span class="link">《实名须知》</span>
          </div>

          <div class="actions">
            <el-button size="large" plain round @click="cancel">取消</el-button>
            <el-button size="large" type="primary" round class="submit-btn" @click="submit">确认添加</el-button>
          </div>
        </el-form>
      </div>
    </div>
  </div>
  <Footer></Footer>
</template>

<script setup lang="ts" name="BuyTicket">
import Header from '@/components/header/index.vue'
import Footer from '@/components/footer/index.vue'
import { ref } from 'vue'
import { saveTicketUser } from "@/api/buyTicketUser"
import { getUserIdKey } from "@/utils/auth"
import { useRouter } from 'vue-router'
import { ID_TYPE } from '@/utils/constants'
import { ElMessage } from 'element-plus'
import { Warning } from '@element-plus/icons-vue'

const router = useRouter()
const formTicketRef = ref<any>(null)
const form = ref<any>({
  relName: '',
  idType: '1',
  idNumber: '',
  userId: ''
})

const rules = ref({
  relName: [{ required: true, message: '请填写观演人姓名', trigger: 'blur' }],
  idNumber: [{ required: true, message: '请填写证件号码', trigger: 'blur' }]
})

const idType = ref(ID_TYPE)

const cancel = () => {
  router.replace({ name: 'orderIndex' })
}

const submit = () => {
  if (!formTicketRef.value) return
  formTicketRef.value.validate((valid: boolean) => {
    if (valid) {
      form.value.userId = getUserIdKey()
      saveTicketUser(form.value).then((response: any) => {
        if (response.code == 0) {
          ElMessage.success('添加成功')
          router.replace({ name: 'orderIndex' })
        } else {
          ElMessage.error(response.message || '保存失败')
        }
      })
    }
  })
}
</script>

<style scoped lang="scss">
.buy-ticket-user-page {
  min-height: calc(100vh - 280px);
  background: var(--tf-bg);
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px 20px;
}

.buy-card {
  width: 480px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-md);
  overflow: hidden;

  &__header {
    padding: 24px 32px 20px;
    border-bottom: 1px solid var(--tf-border);
  }

  &__title {
    font-size: 20px;
    font-weight: 700;
    color: var(--tf-text-primary);
    margin: 0 0 4px 0;
  }

  &__tip {
    font-size: 13px;
    color: var(--tf-text-secondary);
  }

  &__body {
    padding: 32px;
  }
}

.agreement-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--tf-text-secondary);
  margin: 20px 0 28px;

  .icon {
    color: var(--tf-primary);
  }

  .link {
    color: var(--tf-primary);
    cursor: pointer;
  }
}

.actions {
  display: flex;
  gap: 16px;
  justify-content: flex-end;

  .el-button {
    min-width: 110px;
    font-weight: 600;
  }
}
</style>
