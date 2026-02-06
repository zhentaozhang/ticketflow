

<template>
  <el-pagination class="flex-shrink" :background="background" v-model:current-page="currentPage"
  v-model:page-size="pageSize" :layout="layout" :page-sizes="pageSizes" :total="total"
    @size-change="handleSizeChange" @current-change="handleCurrentChange" />
</template>

<script setup>
import {
  ElPagination,
} from 'element-plus';
import { defineProps, computed } from 'vue'
const props = defineProps({
  total: {
    required: true,
    type: Number
  },
  page: {
    type: Number,
    default: 1
  },
  limit: {
    type: Number,
    default: 10
  },
  pageSizes: {
    type: Array,
    default() {
      return [10, 20, 30, 50,100,200,500,1000]
    }
  },
  layout: {
    type: String,
    default: "total, prev, pager, next, sizes" // jumper
  },
  background: {
    type: Boolean,
    default: true
  },
  autoScroll: {
    type: Boolean,
    default: false
  },
  hidden: {
    type: Boolean,
    default: false
  }
})
const emit = defineEmits(['update:page', 'update:limit','pagination'])
const currentPage = computed({
  get: () => props.page,
  set: (value) => {
    emit("update:page", value)
  }
})
const pageSize = computed({
  get: () => props.limit,
  set: (value) => {
    emit("update:limit", value)
  }
})
function handleSizeChange(val) {
  emit("pagination", { page: 1, limit: val })
}
function handleCurrentChange(val) {
  emit("pagination", { page: val, limit: props.limit })
}
</script>
<style scoped lang='scss'></style>
