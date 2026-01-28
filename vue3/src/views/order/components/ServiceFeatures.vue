<template>
  <div class="service-box">
    <span class="service-title">服务</span>
    <div class="service-tags">
      <div class="service-tag" v-if="detailList.permitRefund != ''">
        <span class="service-badge" :class="detailList.permitRefund == '0' ? 'service-badge--warn' : 'service-badge--success'">
          <el-icon v-if="detailList.permitRefund == '0'"><CircleClose /></el-icon>
          <el-icon v-else><CircleCheck /></el-icon>
          {{ detailList.permitRefund == '0' ? '不支持退' : (detailList.permitRefund == '1' ? '条件退' : '全部退') }}
        </span>
      </div>

      <div class="service-tag" v-if="detailList.relNameTicketEntrance != ''">
        <span class="service-badge" :class="detailList.relNameTicketEntrance == '0' ? 'service-badge--info' : 'service-badge--success'">
          <el-icon v-if="detailList.relNameTicketEntrance == '0'"><User /></el-icon>
          <el-icon v-else><UserFilled /></el-icon>
          {{ detailList.relNameTicketEntrance == '0' ? '不实名购票和入场' : '实名购票和入场' }}
        </span>
      </div>

      <div class="service-tag" v-if="detailList.permitChooseSeat != ''">
        <span class="service-badge" :class="detailList.permitChooseSeat == '0' ? 'service-badge--info' : 'service-badge--success'">
          <el-icon><Ticket /></el-icon>
          {{ detailList.permitChooseSeat == '0' ? '不支持选座' : '支持选座' }}
        </span>
      </div>

      <div class="service-tag" v-if="detailList.electronicDeliveryTicket != ''">
        <span class="service-badge service-badge--success">
          <el-icon><Iphone /></el-icon>
          {{ detailList.electronicDeliveryTicket == '0' ? '无票' : (detailList.electronicDeliveryTicket == '1' ? '电子票' : '快递票') }}
        </span>
      </div>

      <div class="service-tag" v-if="detailList.electronicInvoice != ''">
        <span class="service-badge service-badge--success">
          <el-icon><Document /></el-icon>
          {{ detailList.electronicInvoice == '0' ? '纸质发票' : '电子发票' }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { defineProps } from 'vue'
import { CircleClose, CircleCheck, User, UserFilled, Ticket, Iphone, Document } from '@element-plus/icons-vue'

defineProps({
  detailList: {
    type: Object,
    required: true
  }
})
</script>

<style scoped lang="scss">
.service-box {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
  padding: 12px 16px;
  background: #F8FAFC;
  border-radius: var(--tf-radius-md);

  .service-title {
    font-size: 14px;
    font-weight: 700;
    color: var(--tf-text-secondary);
    flex-shrink: 0;
  }

  .service-tags {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .service-badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    font-weight: 600;
    padding: 3px 10px;
    border-radius: var(--tf-radius-full);

    &--success {
      background: #ECFDF5;
      color: #059669;
    }

    &--warn {
      background: #FFF7ED;
      color: #EA580C;
    }

    &--info {
      background: #F1F5F9;
      color: #475569;
    }
  }
}
</style>
