<template>
  <Header></Header>
  <div class="edit-page">
    <div class="edit-card">
      <div class="edit-card__header">邮箱验证</div>
      <div class="edit-card__body">
        <el-form ref="editEmailRef" :model="editEmailForm" :rules="editEmailRules" class="edit-form" label-position="top">
          <el-form-item label="请输入邮箱" prop="email">
            <el-input
                v-model="editEmailForm.email"
                type="email"
                placeholder="请输入邮箱地址"
            ></el-input>
          </el-form-item>
          <el-button
              size="large"
              type="primary"
              class="submit-btn"
              @click.prevent="savePsd"
          >保存</el-button>
        </el-form>
      </div>
    </div>
  </div>
  <Footer class="foot"></Footer>
</template>

<script setup lang="ts">

import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import {ElMessage} from "element-plus"
import {getUserIdKey} from "@/utils/auth"
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {getEditEmailApi} from '@/api/accountSettings'


const router = useRouter();
const editEmailForm = ref({
  email: '',
  id: getUserIdKey()
})
const editEmailRules = ref({
      email: [{
        required: true,
        pattern: /^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,6}$/,
        message: '请输入正确的邮箱',
        trigger: ['blur', 'focus']
      }]
    }
)


function savePsd() {
  getEditEmailApi(editEmailForm.value).then(response => {
    if (response.code == '0') {
      ElMessage({
        message: '保存成功',
        type: 'success',
      })


    } else {
      ElMessage({
        message: response.message,
        type: 'error',
      })
    }
  })
}
</script>

<style scoped lang="scss">
.edit-page {
  min-height: calc(100vh - 300px);
  display: flex;
  justify-content: center;
  align-items: center;
  background: var(--tf-bg);
  padding: 40px 0;
}

.edit-card {
  width: 480px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  box-shadow: var(--tf-shadow-md);
  overflow: hidden;

  &__header {
    padding: 24px 32px;
    font-size: 20px;
    font-weight: 700;
    color: var(--tf-text-primary);
    text-align: center;
    border-bottom: 1px solid var(--tf-border);
  }

  &__body {
    padding: 32px;
  }
}

.edit-form {
  .submit-btn {
    width: 100%;
    margin-top: 16px;
    font-weight: 600;
    letter-spacing: 1px;
  }
}
</style>
