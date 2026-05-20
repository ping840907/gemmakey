# 模型安裝說明

## 步驟

1. 從 [Google AI Edge](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) 或 Kaggle 下載：
   ```
   gemma-4-E2B-it-litert-lm.task
   ```

2. 將模型檔案放入：
   ```
   app/src/main/assets/gemma4/gemma-4-E2B-it-litert-lm.task
   ```
   或使用 adb 複製到裝置內部儲存（路徑：`/data/data/com.gemmakey/files/`）

3. 首次啟動 App 時，模型會自動從 assets 複製到內部儲存以支援 mmap。

## 硬體需求

| 需求 | 規格 |
|------|------|
| RAM | ≥ 6 GB |
| 儲存 | ≥ 5 GB 可用 |
| Android | ≥ 8.0 (API 26) |
| GPU | 建議支援 OpenCL / Vulkan |

## 加速後端優先順序

App 自動依序嘗試：**GPU → CPU+NNAPI → CPU**，並在狀態列顯示目前使用的後端。
