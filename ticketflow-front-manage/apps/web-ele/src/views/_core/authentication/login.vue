<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';
import { computed } from 'vue';
import { AuthenticationLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';
import { useAuthStore } from '#/store';
import type { Recordable } from '@vben/types';
import { ElNotification } from 'element-plus';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();

const formSchema = computed((): VbenFormSchema[] => [
  {
    component: 'VbenInput',
    componentProps: {
      placeholder: $t('authentication.usernameTip'),
    },
    fieldName: 'username',
    defaultValue: 'admin',
    label: $t('authentication.username'),
    rules: z.string().min(1, { message: $t('authentication.usernameTip') }),
  },
  {
    component: 'VbenInputPassword',
    componentProps: {
      placeholder: $t('authentication.password'),
    },
    fieldName: 'password',
    defaultValue: 'admin',
    label: $t('authentication.password'),
    rules: z.string().min(1, { message: $t('authentication.passwordTip') }),
  },
]);

const loginFn = (values: Recordable<any>) => {
  authStore
    .authLogin(values)
    .then((success) => {
      if (!success?.userInfo) {
        ElNotification({
          message: '登录失败，请重试',
          type: 'error',
          title: '错误',
        });
      }
    })
    .catch((error) => {
      console.error('登录失败:', error);
      ElNotification({
        message: '登录失败，请稍后重试',
        type: 'error',
        title: '错误',
      });
    });
};
</script>

<template>
  <div>
    <AuthenticationLogin
      :form-schema="formSchema"
      :loading="authStore.loginLoading"
      :showForgetPassword="false"
      :showRegister="false"
      :showRememberMe="false"
      @submit="loginFn"
    />
  </div>
</template>
