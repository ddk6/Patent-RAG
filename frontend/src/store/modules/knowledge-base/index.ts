import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());

  async function uploadChunk(task: Api.KnowledgeBase.UploadTask): Promise<boolean> {
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    const chunkStart = task.chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    const chunk = task.file.slice(chunkStart, chunkEnd);

    task.chunk = chunk;
    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    const { error, data } = await request<Api.KnowledgeBase.Progress>({
      url: '/upload/chunk',
      method: 'POST',
      data: {
        file: task.chunk,
        fileMd5: task.fileMd5,
        chunkIndex: task.chunkIndex,
        totalSize: task.totalSize,
        fileName: task.fileName,
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false
      },
      headers: {
        'Content-Type': 'multipart/form-data',
        [REQUEST_ID_KEY]: requestId
      },
      timeout: 10 * 60 * 1000
    });

    task.requestIds = task.requestIds.filter(id => id !== requestId);

    if (error) return false;

    // 更新任务状态
    const updatedTask = tasks.value.find(t => t.fileMd5 === task.fileMd5)!;
    updatedTask.uploadedChunks = data.uploaded;
    updatedTask.progress = Number.parseFloat(data.progress.toFixed(2));

    if (data.uploaded.length === totalChunks) {
      const success = await mergeFile(task);
      if (!success) return false;
    }
    return true;
  }

  async function mergeFile(task: Api.KnowledgeBase.UploadTask) {
    try {
      const { error, data } = await request<Api.KnowledgeBase.MergeResult>({
        url: '/upload/merge',
        method: 'POST',
        data: { fileMd5: task.fileMd5, fileName: task.fileName }
      });
      if (error) return false;

      // 更新任务状态为已完成
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      tasks.value[index].status = UploadStatus.Completed;
      tasks.value[index].estimatedEmbeddingTokens = data?.estimatedEmbeddingTokens;
      tasks.value[index].estimatedChunkCount = data?.estimatedChunkCount;

      if (data?.estimatedEmbeddingTokens) {
        const tokenLabel = Number(data.estimatedEmbeddingTokens).toLocaleString();
        const chunkLabel = Number(data.estimatedChunkCount || 0).toLocaleString();
        window.$message?.success(`上传完成，预计向量化消耗 ${tokenLabel} Tokens（${chunkLabel} 个切片）`);
      }
      return true;
    } catch {
      return false;
    }
  }

  function buildUploadTask(form: Api.KnowledgeBase.Form, file: File, fileMd5: string): Api.KnowledgeBase.UploadTask {
    return {
      file,
      chunk: null,
      chunkIndex: 0,
      fileMd5,
      fileName: file.name,
      totalSize: file.size,
      public: form.isPublic,
      isPublic: form.isPublic,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag,
      orgTagName: form.orgTagName ?? null
    };
  }

  function resetBrokenTask(task: Api.KnowledgeBase.UploadTask, form: Api.KnowledgeBase.Form, file: File) {
    Object.assign(task, {
      file,
      chunk: null,
      chunkIndex: task.chunkIndex ?? 0,
      fileName: file.name,
      totalSize: file.size,
      public: form.isPublic,
      isPublic: form.isPublic,
      orgTag: form.orgTag,
      orgTagName: form.orgTagName ?? null,
      status: UploadStatus.Pending
    });
  }

  /** 将一个批次中的多个文件加入上传队列。 */
  async function enqueueUpload(form: Api.KnowledgeBase.Form) {
    const files = (form.fileList || []).map(item => item.file).filter((file): file is File => Boolean(file));

    if (!files.length) {
      window.$message?.error('请选择至少一个文件');
      return { queued: 0, resumed: 0, skipped: 0 };
    }

    let queued = 0;
    let resumed = 0;
    let skipped = 0;

    for (const file of files) {
      // eslint-disable-next-line no-await-in-loop
      const md5 = await calculateMD5(file);

      const existingTask = tasks.value.find(t => t.fileMd5 === md5);
      if (existingTask) {
        if (existingTask.status === UploadStatus.Break) {
          resetBrokenTask(existingTask, form, file);
          resumed += 1;
          startUpload();
        } else {
          skipped += 1;
        }
      } else {
        tasks.value.push(buildUploadTask(form, file, md5));
        queued += 1;
        startUpload();
      }
    }

    const activeCount = queued + resumed;
    if (activeCount > 0) {
      window.$message?.success(`${activeCount} 个文件已加入上传队列`);
    }
    if (skipped > 0) {
      window.$message?.warning(`${skipped} 个文件已跳过，原因是已存在或正在上传`);
    }

    return { queued, resumed, skipped };
  }

  /** 启动文件上传的异步函数 该函数负责从待上传队列中启动文件上传任务，并管理并发上传的数量 */
  async function startUpload() {
    // 限制可同时上传的文件个数
    if (activeUploads.value.size >= 3) return;
    // 获取待上传的文件
    const pendingTasks = tasks.value.filter(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(t.fileMd5)
    );

    // 如果没有待上传的文件，则直接返回
    if (pendingTasks.length === 0) return;

    // 获取第一个待上传的文件
    const task = pendingTasks[0];
    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.fileMd5);

    // 计算文件总片数
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
      }
      // const promises = [];
      // 遍历所有片数
      for (let i = 0; i < totalChunks; i += 1) {
        // 如果未上传，则上传
        if (!task.uploadedChunks.includes(i)) {
          task.chunkIndex = i;
          // promises.push(uploadChunk(task))
          // eslint-disable-next-line no-await-in-loop
          const success = await uploadChunk(task);
          if (!success) throw new Error('分片上传失败');
        }
      }
      // await Promise.all(promises)
    } catch (e) {
      console.error('%c [ 👉 upload error 👈 ]-168', 'font-size:16px; background:#94cc97; color:#d8ffdb;', e);
      // 如果上传失败，则将任务状态设置为中断
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      tasks.value[index].status = UploadStatus.Break;
    } finally {
      // 无论成功或失败，都从活跃队列中移除
      activeUploads.value.delete(task.fileMd5);
      // 继续下一个任务
      startUpload();
    }
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload
  };
});
