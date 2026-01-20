<template>
  <div class="program-list">
    <ul>
      <li v-for="item in (list as any[])" :key="item.id">
        <router-link :to="{ name: 'detail', params: { id: item.id } }" class="poster-link">
          <img :src="item.itemPicture" alt="" class="poster-img" />
        </router-link>
        <div class="item-info">
          <div class="item-title">
            <span class="area-badge">【{{ item.areaName }}】</span>
            <router-link :to="{ name: 'detail', params: { id: item.id } }" class="title-link" v-if="titleIsShow">
              {{ item.title }}
            </router-link>
            <router-link :to="{ name: 'detail', params: { id: item.id } }" class="title-link" v-else v-html="item.title" />
          </div>
          <div class="meta-line" v-if="titleIsShow">艺人：{{ item.actor }}</div>
          <div class="meta-line" v-else><span>艺人：</span><span v-html="item.actor"></span></div>
          <div class="meta-line">📍 {{ item.areaName }} | {{ item.place }}</div>
          <div class="meta-line">📅 {{ formatDateWithWeekday(item.showTime, item.showWeekTime) }}</div>

          <div class="item-footer">
            <div class="price-range">
              <span class="price-symbol">￥</span><strong>{{ item.minPrice }}</strong><span v-if="item.maxPrice"> - {{ item.maxPrice }}</span> <span class="unit">元起</span>
            </div>
            <div class="item-actions">
              <el-tag type="success" size="small" round class="status-tag">售票中</el-tag>
              <router-link :to="{ name: 'detail', params: { id: item.id } }" class="buy-btn">
                抢票 &rarr;
              </router-link>
            </div>
          </div>
        </div>
      </li>
    </ul>
    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="queryParams.pageNum"
      v-model:limit="queryParams.pageSize"
      @pagination="handlePagination"
    />
  </div>
</template>

<script setup lang="ts">
import { defineProps, defineEmits } from 'vue'
import { formatDateWithWeekday } from "@/utils/index"

const props = defineProps({
  list: { type: Array, required: true },
  total: { type: Number, required: true },
  titleIsShow: { type: Boolean, default: true },
  queryParams: { type: Object, required: true }
})

const emit = defineEmits(['pagination'])

function handlePagination() {
  emit('pagination')
}
</script>

<style scoped lang="scss">
.program-list {
  ul {
    margin: 0;
    padding: 16px;
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 20px;

    li {
      display: flex;
      gap: 20px;
      padding: 16px;
      border-radius: var(--tf-radius-md);
      border: 1px solid var(--tf-border);
      background: var(--tf-surface);
      transition: all 0.25s;

      &:hover {
        box-shadow: var(--tf-shadow-md);
        border-color: var(--tf-primary-light);
        
        .poster-img {
          transform: scale(1.03);
        }
      }

      .poster-link {
        flex-shrink: 0;
        width: 140px;
        height: 186px;
        border-radius: var(--tf-radius-md);
        overflow: hidden;
        display: block;
        border: 1px solid var(--tf-border);

        .poster-img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          transition: transform 0.3s ease;
        }
      }

      .item-info {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;

        .item-title {
          font-size: 16px;
          font-weight: 700;
          line-height: 1.4;

          .area-badge {
            color: var(--tf-primary);
          }

          .title-link {
            color: var(--tf-text-primary);
            text-decoration: none;
            transition: color 0.2s;

            &:hover {
              color: var(--tf-primary);
            }
          }
        }

        .meta-line {
          font-size: 13px;
          color: var(--tf-text-secondary);
          overflow: hidden;
          white-space: nowrap;
          text-overflow: ellipsis;
        }

        .item-footer {
          margin-top: auto;
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding-top: 12px;
          border-top: 1px dashed var(--tf-border);

          .price-range {
            font-size: 13px;
            color: var(--tf-text-secondary);

            .price-symbol {
              font-size: 14px;
              color: var(--tf-primary);
              font-weight: 700;
            }

            strong {
              font-size: 22px;
              color: var(--tf-primary);
              font-weight: 800;
            }

            .unit {
              font-size: 12px;
              color: var(--tf-text-muted);
            }
          }

          .item-actions {
            display: flex;
            align-items: center;
            gap: 10px;

            .buy-btn {
              display: inline-flex;
              align-items: center;
              padding: 6px 16px;
              background: var(--tf-primary-light);
              color: var(--tf-primary);
              font-size: 13px;
              font-weight: 700;
              border-radius: var(--tf-radius-full);
              text-decoration: none;
              transition: all 0.25s;

              &:hover {
                background: var(--tf-primary);
                color: #ffffff;
                box-shadow: 0 4px 12px rgba(255, 55, 29, 0.3);
                transform: translateY(-1px);
              }
            }
          }
        }
      }
    }
  }
}
</style>
