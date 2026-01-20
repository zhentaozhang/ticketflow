<template>
  <div class="tf-sidebar">
    <div class="tf-sidebar__header">我的 TicketFlow</div>
    <el-menu
      :default-openeds="defaultOpened"
      class="tf-sidebar__menu"
      :default-active="activeIndex"
    >
      <el-sub-menu index="transaction">
        <template #title>
          <el-icon><Tickets /></el-icon>
          <span>交易中心</span>
        </template>
        <el-menu-item index="5">
          <router-link to="/orderManagement/index">订单管理</router-link>
        </el-menu-item>
      </el-sub-menu>

      <el-sub-menu index="account">
        <template #title>
          <el-icon><User /></el-icon>
          <span>账户中心</span>
        </template>
        <el-menu-item index="2">
          <router-link to="/accountSettings/index">账号设置</router-link>
        </el-menu-item>
        <el-menu-item index="3">
          <router-link to="/personInfo/index">个人信息</router-link>
        </el-menu-item>
        <el-menu-item index="4">
          <router-link to="/personInfo/ticketUser">常用购票人</router-link>
        </el-menu-item>
      </el-sub-menu>
    </el-menu>
  </div>
</template>

<script setup lang="ts">
import { Tickets, User } from '@element-plus/icons-vue'
import { ref, defineProps } from 'vue'

const props = defineProps<{
  activeIndex?: string
}>()

const defaultOpened = ref(['transaction', 'account'])
</script>

<style scoped lang="scss">
.tf-sidebar {
  width: 220px;
  background: var(--tf-surface);
  border-radius: var(--tf-radius-lg);
  border: 1px solid var(--tf-border);
  box-shadow: var(--tf-shadow-sm);
  overflow: hidden;

  &__header {
    padding: 16px 20px;
    font-size: 16px;
    font-weight: 600;
    color: var(--tf-text-primary);
    background: var(--tf-bg);
    border-bottom: 1px solid var(--tf-border);
  }

  &__menu {
    border-right: none;
    
    :deep(.el-sub-menu__title) {
      font-weight: 500;
      color: var(--tf-text-primary);
    }

    :deep(.el-menu-item) {
      padding: 0;
      min-width: 0;
      
      a {
        display: block;
        padding: 0 20px 0 40px;
        width: 100%;
        height: 100%;
        color: var(--tf-text-secondary);
        text-decoration: none;
        transition: all 0.2s;
        
        &:hover {
          color: var(--tf-primary);
        }
      }

      &.is-active a {
        color: var(--tf-primary);
        background: var(--tf-primary-light);
        font-weight: 500;
        border-right: 3px solid var(--tf-primary);
      }
    }
  }
}
</style>
