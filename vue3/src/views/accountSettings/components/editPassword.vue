<template>
  <Header></Header>
  <div class="edit-page">
    <div class="edit-card">
      <div class="edit-card__header">修改登录密码</div>
      <div class="edit-card__body">
        <el-form ref="editPsdRef" :model="editPsdForm" :rules="editPsdRules" class="edit-form" label-position="top">
          <el-form-item label="输入原密码" prop="oldPassword">
            <el-input
                v-model="editPsdForm.oldPassword"
                type="password"
                show-password
                placeholder="请输入当前登录密码"
            ></el-input>
          </el-form-item>
          <el-form-item label="输入新密码" prop="password">
            <el-input
                v-model="editPsdForm.password"
                type="password"
                show-password
                placeholder="请输入新密码"
            ></el-input>
          </el-form-item>
          <el-button
              size="large"
              type="primary"
              class="submit-btn"
              @click.prevent="savePsd"
          >保存修改</el-button>
        </el-form>
      </div>
    </div>
  </div>
  <Footer class="foot"></Footer>
</template>

<script setup lang="ts">

import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import {getEditPsdApi} from '@/api/accountSettings'
import {ElMessage} from "element-plus"
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import { useAuthStore } from '@/store/modules/auth';


const router = useRouter();
const userStore = useAuthStore()
// 身份由网关按登录态注入，前端不再传 id；改密必须带上原密码做二次校验
const editPsdForm = ref({
  oldPassword: '',
  password: ''
})
const editPsdRules = ref({
  oldPassword: [
    { required: true, message: '请输入原密码', trigger: 'blur' }
  ],
  password: [
    {
      required: true,
      pattern: /^(?![\d]+$)(?![a-zA-Z]+$)(?![^\da-zA-Z]+$)([^\u4e00-\u9fa5\s]){6,20}$/,
      message: '6-20位英文字母、数字或者符号（除空格），且字母、数字和标点符号至少包含两种',
      trigger: ['blur', 'focus']
    }
  ]
})


function savePsd() {
  getEditPsdApi(editPsdForm.value).then(response=>{
    if(response.code == '0'){
      ElMessage({
        message: '保存成功',
        type: 'success',
      })

      userStore.logOut().then(() => {
        location.href = '../../login';
      })

    }else{
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
