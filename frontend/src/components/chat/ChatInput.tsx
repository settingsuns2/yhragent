import * as React from "react";
import { Brain, Lightbulb, Paperclip, Send, Square, X } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import {
  uploadFilesToWorkspace,
  readFileContent,
  formatFileSize,
  type UploadedFile
} from "@/services/fileUploadService";
import { toast } from "sonner";

interface PendingFile {
  file: File;
  status: "pending" | "uploading" | "reading" | "done" | "error";
  uploaded?: UploadedFile;
  content?: string;
  error?: string;
}

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [pendingFiles, setPendingFiles] = React.useState<PendingFile[]>([]);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    deepThinkingEnabled,
    setDeepThinkingEnabled,
    inputFocusKey
  } = useChatStore();

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

  const handleFileSelect = () => {
    fileInputRef.current?.click();
  };

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = event.target.files;
    if (!files || files.length === 0) return;

    const newFiles: PendingFile[] = Array.from(files).map((file) => ({
      file,
      status: "pending" as const
    }));

    setPendingFiles((prev) => [...prev, ...newFiles]);
    event.target.value = "";
  };

  const removePendingFile = (index: number) => {
    setPendingFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }

    const hasFiles = pendingFiles.some((f) => f.status !== "error");
    if (!value.trim() && !hasFiles) return;

    const currentFiles = pendingFiles.filter((f) => f.status !== "error");

    const uploadResults = await uploadFilesToWorkspace(
      currentFiles.map((f) => f.file)
    ).catch((err) => {
      console.warn("文件上传失败:", err);
      return [];
    });

    const fileContext = await buildFileContextAsync(currentFiles, uploadResults);
    const message = value.trim() + fileContext;

    if (!message.trim()) {
      toast.error("消息内容为空");
      return;
    }

    setValue("");
    setPendingFiles([]);
    focusInput();
    await sendMessage(message);
    focusInput();
  };

  const buildFileContextAsync = async (
    files: PendingFile[],
    uploadResults: UploadedFile[]
  ): Promise<string> => {
    if (files.length === 0) return "";
    const parts: string[] = [];
    parts.push("\n\n[用户上传了以下文件到工作空间]");
    for (let i = 0; i < files.length; i++) {
      const pf = files[i];
      const uploaded = uploadResults[i];
      const workspacePath = uploaded?.path || pf.file.name;
      const meta = `- 文件: ${pf.file.name}, 工作空间路径: ${workspacePath} (${formatFileSize(pf.file.size)})`;
      const content = await readFileContent(pf.file);
      if (content != null) {
        const lines = content.split("\n").length;
        parts.push(`${meta}, ${lines} 行`);
        parts.push(`--- 开始 ${pf.file.name} ---`);
        parts.push(content);
        parts.push(`--- 结束 ${pf.file.name} ---`);
      } else {
        parts.push(`${meta} (二进制文件，请使用 file_manager 工具的 action=read 读取工作空间路径的文件)`);
      }
    }
    return parts.join("\n");
  };

  const hasContent = value.trim().length > 0 || pendingFiles.some((f) => f.status !== "error");

  return (
    <div className="space-y-4">
      <div
        className={cn(
          "relative flex flex-col rounded-2xl border bg-white px-4 pt-3 pb-2 transition-all duration-200",
          isFocused
            ? "border-[#D4D4D4] shadow-[0_4px_12px_rgba(0,0,0,0.06)]"
            : "border-[#E5E5E5] hover:border-[#D4D4D4]"
        )}
      >
        {pendingFiles.length > 0 && (
          <div className="mb-2 flex flex-wrap gap-2">
            {pendingFiles.map((pf, index) => (
              <div
                key={index}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs",
                  pf.status === "done"
                    ? "border-green-200 bg-green-50 text-green-700"
                    : pf.status === "error"
                      ? "border-red-200 bg-red-50 text-red-600"
                      : pf.status === "uploading"
                        ? "border-blue-200 bg-blue-50 text-blue-600"
                        : "border-gray-200 bg-gray-50 text-gray-600"
                )}
              >
                <Paperclip className="h-3 w-3 shrink-0" />
                <span className="max-w-[120px] truncate">{pf.file.name}</span>
                <span className="text-[10px] opacity-70">
                  {formatFileSize(pf.file.size)}
                </span>
                {pf.status === "uploading" && (
                  <span className="ml-1 h-2 w-2 animate-spin rounded-full border border-blue-400 border-t-transparent" />
                )}
                {pf.status === "done" && <span className="text-green-500">✓</span>}
                {pf.status === "error" && (
                  <span className="text-red-400" title={pf.error}>✗</span>
                )}
                <button
                  type="button"
                  onClick={() => removePendingFile(index)}
                  className="ml-0.5 rounded p-0.5 hover:bg-black/5"
                >
                  <X className="h-3 w-3" />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="relative">
          <Textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
            className="max-h-40 min-h-[44px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 pr-2 text-[15px] text-[#333333] shadow-none placeholder:text-[#999999] focus-visible:ring-0"
            rows={1}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={() => {
              isComposingRef.current = false;
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                const nativeEvent = event.nativeEvent as KeyboardEvent;
                if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                  return;
                }
                event.preventDefault();
                handleSubmit();
              }
            }}
            aria-label="聊天输入框"
          />
          <div className="pointer-events-none absolute bottom-0 left-0 right-0 h-[10px] bg-gradient-to-b from-white/0 via-white/40 to-white/90" />
        </div>

        <div className="relative mt-2 flex items-center">
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
              disabled={isStreaming}
              aria-pressed={deepThinkingEnabled}
              className={cn(
                "rounded-lg border px-3 py-1.5 text-xs font-medium transition-all",
                deepThinkingEnabled
                  ? "border-[#BFDBFE] bg-[#DBEAFE] text-[#2563EB]"
                  : "border-transparent bg-[#F5F5F5] text-[#999999] hover:bg-[#EEEEEE]",
                isStreaming && "cursor-not-allowed opacity-60"
              )}
            >
              <span className="inline-flex items-center gap-2">
                <Brain className={cn("h-3.5 w-3.5", deepThinkingEnabled && "text-[#3B82F6]")} />
                深度思考
                {deepThinkingEnabled ? (
                  <span className="h-2 w-2 rounded-full bg-[#3B82F6] animate-pulse" />
                ) : null}
              </span>
            </button>

            <button
              type="button"
              onClick={handleFileSelect}
              disabled={isStreaming}
              className={cn(
                "rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-all",
                "border-transparent bg-[#F5F5F5] text-[#999999] hover:bg-[#EEEEEE] hover:text-[#666666]",
                isStreaming && "cursor-not-allowed opacity-60",
                pendingFiles.length > 0 && "border-[#BFDBFE] bg-[#DBEAFE] text-[#2563EB]"
              )}
              title="上传文件到工作空间"
            >
              <span className="inline-flex items-center gap-1.5">
                <Paperclip className="h-3.5 w-3.5" />
                {pendingFiles.length > 0 ? `${pendingFiles.length} 个文件` : "附件"}
              </span>
            </button>
          </div>

          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={handleFileChange}
            accept=".java,.py,.js,.ts,.tsx,.jsx,.xml,.yaml,.yml,.json,.properties,.md,.txt,.csv,.html,.css,.sql,.sh,.bat,.gradle,.toml,.cfg,.ini,.conf,.go,.rs,.c,.cpp,.h,.hpp,.zip,.tar,.gz,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
          />

          <button
            type="button"
            onClick={handleSubmit}
            disabled={!hasContent && !isStreaming}
            aria-label={isStreaming ? "停止生成" : "发送消息"}
            className={cn(
              "ml-auto rounded-full p-2.5 transition-all duration-200",
              isStreaming
                ? "bg-[#FEE2E2] text-[#EF4444] hover:bg-[#FECACA]"
                : hasContent
                  ? "bg-[#3B82F6] text-white hover:bg-[#2563EB]"
                  : "cursor-not-allowed bg-[#F5F5F5] text-[#CCCCCC]"
            )}
          >
            {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
          </button>
        </div>
      </div>
      {deepThinkingEnabled ? (
        <p className="text-xs text-[#2563EB]">
          <span className="inline-flex items-center gap-1.5">
            <Lightbulb className="h-3.5 w-3.5" />
            深度思考模式已开启，AI将进行更深入的分析推理
          </span>
        </p>
      ) : null}
      <p className="text-center text-xs text-[#999999]">
        <kbd className="rounded bg-[#F5F5F5] px-1.5 py-0.5 text-[#666666]">Enter</kbd> 发送
        <span className="px-1.5">·</span>
        <kbd className="rounded bg-[#F5F5F5] px-1.5 py-0.5 text-[#666666]">
          Shift + Enter
        </kbd>{" "}
        换行
        {isStreaming ? <span className="ml-2 animate-pulse-soft">生成中...</span> : null}
      </p>
    </div>
  );
}
