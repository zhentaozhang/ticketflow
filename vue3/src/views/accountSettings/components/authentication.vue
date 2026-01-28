<template>
  <Header></Header>
  <el-row>
    <el-form ref="authRef" :model="authForm" :rules="authRules" class="login-form">
      <el-col :span="24">
        <el-form-item label="请输入真实姓名:" prop="relName">
          <el-input
              v-model="authForm.relName"
              class="input-with-select"
              type="text"
          ></el-input>
        </el-form-item>
        <el-form-item label="请输入身份证号码:" prop="idNumber">
          <el-input
              v-model="authForm.idNumber"
              class="input-with-select"
              type="text"
          ></el-input>
        </el-form-item>
      </el-col>
      <el-button
          size="large"
          type="primary"
          class="btn"
          @click.prevent="savePsd"
      ><span>保存</span></el-button>
    </el-form>
  </el-row>
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
import {getAuthenticationApi} from '@/api/accountSettings'


const router = useRouter();
const userStore = useAuthStore()
const authForm = ref({
  idNumber: '',
  relName: '',
  id: getUserIdKey()
})


const authRules = ref({
      idNumber: [{ required: true, message: "请输入身份证号码", trigger: "blur" }],
      relName: [{ required: true, message: "请输入真实姓名", trigger: "blur" }],
    }
)


function savePsd() {
  getAuthenticationApi(authForm.value).then(response => {
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
.el-row {
  width: 400px;
  height: 400px;
  margin: 100px auto 30px;
}

.btn {
  margin-left: 130px;
  background: var(--brand-color);
  border: none;
}
</style>
