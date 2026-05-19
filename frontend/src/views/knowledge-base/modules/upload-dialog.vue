<script setup lang="ts">
import type { UploadFileInfo } from 'naive-ui';
import { uploadAccept } from '@/constants/common';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });
const singleOrgOnly = ref(false);

const authStore = useAuthStore();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    orgTag: null,
    orgTagName: '',
    uploadMaxSizeBytes: null,
    uploadMaxSizeMb: null,
    isPublic: false,
    fileList: []
  };
}

const rules = ref<FormRules>({
  orgTag: defaultRequiredRule,
  isPublic: defaultRequiredRule,
  fileList: [
    {
      validator: (_rule, value: UploadFileInfo[]) => Array.isArray(value) && value.some(item => item.file),
      message: '请选择至少一个文件',
      trigger: ['change', 'blur']
    }
  ]
});

const selectedFiles = computed(() => {
  return (model.value.fileList || []).map(item => item.file).filter((file): file is File => Boolean(file));
});

const batchTotalSize = computed(() => selectedFiles.value.reduce((sum, file) => sum + file.size, 0));
const batchSummary = computed(() => {
  if (!selectedFiles.value.length) return '';
  return `已选择 ${selectedFiles.value.length} 个文件，总大小 ${formatFileSize(batchTotalSize.value)}`;
});

const fileSizeLimitErrors = computed(() => {
  if (authStore.isAdmin || !model.value.uploadMaxSizeBytes) return [];

  return selectedFiles.value
    .filter(file => file.size > model.value.uploadMaxSizeBytes!)
    .map(file => `${file.name}（${formatFileSize(file.size)}）`);
});

const fileSizeLimitError = computed(() => {
  if (!fileSizeLimitErrors.value.length) return '';
  return `当前组织限制非管理员上传文件不超过 ${model.value.uploadMaxSizeMb} MB，以下文件超限：${fileSizeLimitErrors.value.join('、')}`;
});

const submitDisabled = computed(
  () => loading.value || selectedFiles.value.length === 0 || Boolean(fileSizeLimitError.value)
);

function formatFileSize(size: number) {
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(2)} KB`;
  }
  if (size < 1024 * 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(2)} MB`;
  }
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function close() {
  visible.value = false;
}

const store = useKnowledgeBaseStore();
async function handleSubmit() {
  await validate();
  if (fileSizeLimitError.value) return;

  loading.value = true;
  try {
    await store.enqueueUpload(model.value);
    close();
  } finally {
    loading.value = false;
  }
}

async function presetSingleOrgForUser() {
  singleOrgOnly.value = false;
  const { error, data } = await request<Api.OrgTag.Mine>({ url: '/users/org-tags' });
  if (error || !visible.value) return;

  const orgTagDetails = data.orgTagDetails || [];
  if (orgTagDetails.length !== 1) return;

  const singleOrg = orgTagDetails[0];
  model.value.orgTag = singleOrg.tagId;
  onUpdate(singleOrg);
  singleOrgOnly.value = true;
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
    singleOrgOnly.value = false;
    if (!authStore.isAdmin) {
      presetSingleOrgForUser();
    }
    restoreValidation();
  }
});

function onUpdate(option: unknown) {
  if (option) {
    const selected = option as Api.OrgTag.Item;
    model.value.orgTagName = selected.name;
    model.value.uploadMaxSizeBytes = selected.uploadMaxSizeBytes;
    model.value.uploadMaxSizeMb = selected.uploadMaxSizeMb;
    return;
  }
  model.value.orgTagName = '';
  model.value.uploadMaxSizeBytes = null;
  model.value.uploadMaxSizeMb = null;
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="批量上传"
    :show-icon="false"
    :mask-closable="false"
    class="w-620px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem v-if="authStore.isAdmin" label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" @change="onUpdate" />
      </NFormItem>
      <NFormItem v-else label="组织标签" path="orgTag">
        <TheSelect
          v-model:value="model.orgTag"
          url="/users/org-tags"
          key-field="orgTagDetails"
          label-field="name"
          value-field="tagId"
          :disabled="singleOrgOnly"
          @change="onUpdate"
        />
      </NFormItem>

      <NFormItem label="是否公开" path="isPublic">
        <NRadioGroup v-model:value="model.isPublic" name="radiogroup">
          <NSpace :size="16">
            <NRadio :value="true">公开</NRadio>
            <NRadio :value="false">私有</NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>
      <NFormItem label="上传文件" path="fileList">
        <NUpload v-model:file-list="model.fileList" :accept="uploadAccept" :multiple="true" :default-upload="false">
          <NButton>选择文件</NButton>
        </NUpload>
        <div v-if="batchSummary" class="mt-8px text-12px text-#64748b">
          {{ batchSummary }}
        </div>
        <div v-if="fileSizeLimitError" class="mt-8px text-12px text-#ef4444 leading-5">
          {{ fileSizeLimitError }}
        </div>
        <div v-else-if="!authStore.isAdmin && model.uploadMaxSizeMb" class="mt-8px text-12px text-#d97706">
          当前组织限制非管理员上传文件不超过 {{ model.uploadMaxSizeMb }} MB
        </div>
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" :disabled="submitDisabled" @click="handleSubmit">开始上传</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
