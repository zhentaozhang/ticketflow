<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { CustomizeDataApi } from '#/api/customize';

import { ref, nextTick, h, computed } from 'vue';

import { Page, useVbenDrawer } from '@vben/common-ui';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { messageRecordPageApi, executeExceptionMessageApi } from '#/api/customize';
import { programPageQueryApi, selectByTypeQueryApi, selectByParentProgramCategoryIdQueryApi } from '#/api/program';
import { areaManageListQueryApi } from '#/api/base-data';
import { $t } from '#/locales';

import { useColumns } from './data';

// 表单组件，显示列表
const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    wrapperClass: 'grid-cols-2',
    commonConfig: {
      controlClass: 'max-w-[260px] w-full',
      formItemClass: 'col-span-1',
    },
    schema: [
      {
        component: 'Input',
        fieldName: 'programTitle',
        label: '节目',
        componentProps: {
          readonly: true,
          placeholder: '请选择节目',
          onClick: () => openProgramPicker(),
        },
        renderComponentContent: () => ({
          suffix: () => h('span', { class: 'text-blue-600 cursor-pointer', onClick: () => openProgramPicker() }, '选择'),
        }),
        formItemClass: 'col-span-2',
      },
      // 隐藏字段：存储节目ID用于查询
      {
        component: 'Input',
        fieldName: 'programId',
        label: '',
        componentProps: {
          readonly: true,
        },
        formItemClass: 'hidden',
      },
    ],
    showCollapseButton: false,
    submitOnChange: true,
  },
  gridOptions: {
    columns: useColumns(),
    height: '100%',
    keepSource: true,
    pagerConfig: {
      enabled: true,
      pageSize: 20,
      pageSizes: [10, 20, 50, 100],
      currentPage: 1,
    },
    proxyConfig: {
      autoLoad: false,
      ajax: {
        query: async ({ page }, formValues) => {
          const programId = (formValues as any)?.programId as string | undefined;
          if (!programId) {
            return { items: [], total: 0 } as any;
          }
          const res: any = await messageRecordPageApi({
            messageBusinessesId: programId,
            pageNumber: page.currentPage,
            pageSize: page.pageSize,
          } as any);
          const data = res?.data ?? res;
          const items = Array.isArray(data?.records)
            ? data.records
            : Array.isArray(data?.list)
            ? data.list
            : Array.isArray(data?.items)
            ? data.items
            : [];
          const total = Number(
            data?.total ?? data?.totalSize ?? (Array.isArray(items) ? items.length : 0),
          );
          return { items, total } as any;
        },
      },
    },
    rowConfig: {
      keyField: 'messageTraceId',
    },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: { code: 'query' },
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<CustomizeDataApi.MessageRecordVo>,
});

// 节目选择弹窗相关
const [ProgramPicker, programPickerApi] = useVbenDrawer();
const programList = ref<any[]>([]);
const programPager = ref({ page: 1, size: 10, total: 0 });
const programPageSizeOptions = [10, 20, 50, 100];
const programTotalPages = computed(() => {
  const t = Number(programPager.value.total || 0);
  const s = Number(programPager.value.size || 10);
  return s > 0 ? Math.max(1, Math.ceil(t / s)) : 1;
});
// 选择器筛选项
const areaOptions = ref<Array<{ label: string; value: string }>>([]);
const categoryOptions = ref<Array<{ label: string; value: string }>>([]);
const childCategoryOptions = ref<Array<{ label: string; value: string }>>([]);
// 当前筛选值
const pickerAreaId = ref<string>('');
const pickerParentCategoryId = ref<string>('');
const pickerCategoryId = ref<string>('ALL');

async function loadPrograms() {
  const res: any = await programPageQueryApi({
    areaId: String(pickerAreaId.value || ''),
    pageNumber: String(programPager.value.page),
    pageSize: String(programPager.value.size),
    parentProgramCategoryId: String(pickerParentCategoryId.value || ''),
    programCategoryId: pickerCategoryId.value && pickerCategoryId.value !== 'ALL' ? String(pickerCategoryId.value) : '',
    timeType: '0',
    type: '1',
  } as any);
  const data = res?.data ?? res;
  const list = Array.isArray(data?.list) ? data.list : [];
  programList.value = list;
  // 兼容后端返回的字符串型分页字段
  programPager.value.total = Number((data as any)?.totalSize ?? (data as any)?.total ?? list.length);
  if ((data as any)?.pageNum != null) {
    programPager.value.page = Number((data as any)?.pageNum);
  }
  if ((data as any)?.pageSize != null) {
    programPager.value.size = Number((data as any)?.pageSize);
  }
}

function openProgramPicker() {
  programPickerApi.open();
  nextTick(() => {
    initPickerFilters();
  });
}

function chooseProgram(row: any) {
  const form = (gridApi as any)?.formApi;
  form?.setFieldValue?.('programId', String(row?.id ?? ''));
  form?.setFieldValue?.('programTitle', String(row?.title ?? ''));
  programPickerApi.close();
  // 直接触发表单提交（Grid将自动发起查询）
  (gridApi as any)?.formApi?.submit?.();
}

async function initPickerFilters() {
  // 并行拉取区域与一级分类
  try {
    const [cityResult, categoryResult] = await Promise.allSettled([
      areaManageListQueryApi(),
      selectByTypeQueryApi({ type: '1' } as any),
    ]);

    if (cityResult.status === 'fulfilled') {
      const list: any = cityResult.value;
      const arr = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : [];
      areaOptions.value = arr.map((item: any) => ({ label: item.name, value: String(item.id) }));
      pickerAreaId.value = areaOptions.value?.[0]?.value || '';
    } else {
      areaOptions.value = [];
      pickerAreaId.value = '';
    }

    if (categoryResult.status === 'fulfilled') {
      const list: any = categoryResult.value;
      const arr = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : [];
      categoryOptions.value = arr.map((item: any) => ({ label: item.name, value: String(item.id) }));
      pickerParentCategoryId.value = categoryOptions.value?.[0]?.value || '';
    } else {
      categoryOptions.value = [];
      pickerParentCategoryId.value = '';
    }

    // 初始化子类
    await loadChildOptions(pickerParentCategoryId.value);
    pickerCategoryId.value = 'ALL';
  } finally {
    await loadPrograms();
  }
}

async function loadChildOptions(parentId: string | undefined) {
  if (!parentId) {
    childCategoryOptions.value = [{ label: '全部', value: 'ALL' }];
    return;
  }
  try {
    const list: any = await selectByParentProgramCategoryIdQueryApi({ parentProgramCategoryId: parentId } as any);
    const arr = Array.isArray(list) ? list : Array.isArray(list?.data) ? list.data : [];
    const mapped = arr.map((item: any) => ({ label: item.name, value: String(item.id) }));
    childCategoryOptions.value = [{ label: '全部', value: 'ALL' }, ...mapped];
  } catch (error) {
    childCategoryOptions.value = [{ label: '全部', value: 'ALL' }];
  }
}

async function onHandleException(row: any) {
  try {
    // 确认操作
    await ElMessageBox.confirm(
      `确定要处理消息ID为 ${row.messageId} 的异常消息吗？`,
      '确认处理',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }
    );
    
    // 调用处理异常消息API
    const result = await executeExceptionMessageApi({
      messageId: row.messageId
    });
    
    if (result) {
      // 处理成功，刷新列表
      gridApi.reload();
      ElMessage.success('异常消息处理成功');
    } else {
      // 处理失败，显示错误提示
      ElMessage.error('异常消息处理失败');
    }
  } catch (error: any) {
    // 用户取消操作或发生错误
    if (error !== 'cancel') {
      console.error('处理异常消息时发生错误:', error);
      ElMessage.error('处理异常消息时发生错误');
    }
  }
}

// 无分层展开需求，移除相关函数
</script>
<template>
  <Page auto-content-height>
    <Grid :table-title="$t('ticketflow.index.messageRecordListTitle')">
      <template #orderNumberCell="{ row }">
        <span class="text-gray-800">{{ row.messageProducerRecordId }}</span>
      </template>
      <template #reconciliationStatusCell="{ row }">
        <span 
          :class="{
            'text-yellow-600': row.reconciliationStatus === '1',
            'text-red-600': row.reconciliationStatus === '-1',
            'text-green-600': row.reconciliationStatus === '2' || row.reconciliationStatus === '3',
            'text-gray-800': !['1', '-1', '2', '3'].includes(row.reconciliationStatus)
          }"
        >
          {{ row.reconciliationStatusName }}
        </span>
      </template>
      <template #operationCell="{ row }">
        <div v-if="row.reconciliationStatus === '-1'">
          <button 
            class="text-blue-600 hover:text-blue-800 cursor-pointer px-2 py-1 border border-blue-300 rounded hover:bg-blue-50"
            @click="onHandleException(row)"
          >
            处理异常
          </button>
        </div>
        <div v-else>
          <span class="text-gray-400 px-2 py-1">无需处理</span>
        </div>
      </template>
    </Grid>
    <ProgramPicker class="w-[900px]" title="选择节目">
      <div class="p-4">
        <div class="mb-3 text-sm text-gray-600">请选择区域/分类/子类筛选节目，然后点击“选择”</div>
        <div class="grid grid-cols-3 gap-3 mb-4">
          <div>
            <label class="block text-xs text-gray-500 mb-1">区域</label>
            <select
              class="w-full border rounded px-2 py-1 h-8"
              v-model="pickerAreaId"
              @change="loadPrograms()"
            >
              <option v-for="opt in areaOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">分类</label>
            <select
              class="w-full border rounded px-2 py-1 h-8"
              v-model="pickerParentCategoryId"
              @change="(async () => { await loadChildOptions(pickerParentCategoryId); pickerCategoryId = 'ALL'; await loadPrograms(); })()"
            >
              <option v-for="opt in categoryOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">子类</label>
            <select
              class="w-full border rounded px-2 py-1 h-8"
              v-model="pickerCategoryId"
              @change="loadPrograms()"
            >
              <option v-for="opt in childCategoryOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
        </div>
        <div class="border rounded">
          <table class="table-fixed w-full text-left text-sm">
            <thead>
              <tr>
                <th class="py-2 px-3 w-[20%]">节目ID</th>
                <th class="py-2 px-3 w-[40%]">标题</th>
                <th class="py-2 px-3 w-[20%]">城市</th>
                <th class="py-2 px-3 w-[20%]">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in programList" :key="p.id" class="hover:bg-gray-50">
                <td class="py-2 px-3">{{ p.id }}</td>
                <td class="py-2 px-3">{{ p.title }}</td>
                <td class="py-2 px-3">{{ p.areaName }}</td>
                <td class="py-2 px-3">
                  <button class="text-blue-600" @click="chooseProgram(p)">选择</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="flex items-center justify-between mt-3">
          <div class="text-sm text-gray-600">
            共 {{ programPager.total }} 条，页 {{ programPager.page }} / {{ programTotalPages }}
          </div>
          <div class="flex items-center gap-2">
            <label class="text-sm text-gray-600">每页</label>
            <select
              class="border rounded px-2 py-1 h-8"
              v-model.number="programPager.size"
              @change="(async () => { programPager.page = 1; await loadPrograms(); })()"
            >
              <option v-for="s in programPageSizeOptions" :key="s" :value="s">{{ s }}</option>
            </select>
            <button
              class="px-3 py-1 border rounded disabled:opacity-50"
              :disabled="programPager.page <= 1"
              @click="(async () => { programPager.page = Math.max(1, programPager.page - 1); await loadPrograms(); })()"
            >上一页</button>
            <button
              class="px-3 py-1 border rounded disabled:opacity-50"
              :disabled="programPager.page >= programTotalPages"
              @click="(async () => { programPager.page = Math.min(programTotalPages, programPager.page + 1); await loadPrograms(); })()"
            >下一页</button>
          </div>
        </div>
      </div>
    </ProgramPicker>
  </Page>
</template>
