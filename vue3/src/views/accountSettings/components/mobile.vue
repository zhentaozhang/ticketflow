<template>
  <Header></Header>
  <div class="edit-page">
    <div class="edit-card">
      <div class="edit-card__header">手机号验证</div>
      <div class="edit-card__body">
        <el-form ref="editMobileRef" :model="editMobileForm" :rules="editMobileRules" class="edit-form" label-position="top">
          <el-form-item label="请输入手机号码" prop="mobile">
            <el-input
                v-model="editMobileForm.mobile"
                type="text"
                placeholder="请输入手机号码"
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
import { useAuthStore } from '@/store/modules/auth';
import {getEditMobileApi} from '@/api/accountSettings'


const router = useRouter();
const userStore = useAuthStore()
const editMobileForm = ref({
  mobile: '',
  id: getUserIdKey()
})

const validatePhone = (rule, value, callback) => {
  const reg = /^1[3-9]\d{9}$/;
  if (!value) {
    return callback(new Error('手机号码不能为空'));
  } else if (!reg.test(value)) {
    return callback(new Error('请输入正确的手机号码'));
  } else {
    callback();
  }
};
const editMobileRules = ref({
      mobile: [{required: true, trigger: "blur", validator: validatePhone}]
    }
)


function savePsd() {
  getEditMobileApi(editMobileForm.value).then(response => {
    if (response.code == '0') {
      ElMessage({
        message: '保存成功',
        type: 'success',
      })

      userStore.logOut().then(() => {
        location.href = '../../login';
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
