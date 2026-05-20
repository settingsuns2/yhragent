import { api } from "./api";

const MCP_FILES_BASE = import.meta.env.VITE_MCP_SERVER_URL || "http://localhost:9099";

export interface UploadedFile {
  name: string;
  path: string;
  size: number;
  contentType: string;
}

export async function uploadFileToWorkspace(
  file: File,
  subDir: string = ""
): Promise<UploadedFile> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("subDir", subDir);

  const response = await fetch(`${MCP_FILES_BASE}/files/upload`, {
    method: "POST",
    body: formData
  });

  if (!response.ok) {
    throw new Error(`文件上传失败: ${response.statusText}`);
  }

  return response.json();
}

export async function uploadFilesToWorkspace(
  files: File[],
  subDir: string = ""
): Promise<UploadedFile[]> {
  const formData = new FormData();
  files.forEach((file) => formData.append("files", file));
  formData.append("subDir", subDir);

  const response = await fetch(`${MCP_FILES_BASE}/files/upload-batch`, {
    method: "POST",
    body: formData
  });

  if (!response.ok) {
    throw new Error(`批量上传失败: ${response.statusText}`);
  }

  return response.json();
}

export function getFileDownloadUrl(filePath: string): string {
  return `${MCP_FILES_BASE}/files/download/${filePath}`;
}

export function formatFileSize(bytes: number): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}

const TEXT_EXTENSIONS = new Set([
  ".java", ".py", ".js", ".ts", ".tsx", ".jsx", ".xml", ".yaml", ".yml",
  ".json", ".properties", ".md", ".txt", ".csv", ".html", ".css", ".sql",
  ".sh", ".bat", ".gradle", ".toml", ".cfg", ".ini", ".conf", ".go",
  ".rs", ".c", ".cpp", ".h", ".hpp", ".log", ".env", ".gitignore",
  ".dockerfile", ".makefile", ".cmake"
]);

const MAX_INLINE_SIZE = 200 * 1024;

function isTextFile(file: File): boolean {
  const name = file.name.toLowerCase();
  if (TEXT_EXTENSIONS.has(name)) return true;
  const dotIdx = name.lastIndexOf(".");
  if (dotIdx >= 0) {
    const ext = name.substring(dotIdx);
    if (TEXT_EXTENSIONS.has(ext)) return true;
  }
  return file.type.startsWith("text/") || file.type === "application/json"
    || file.type === "application/xml" || file.type === "application/javascript";
}

export function readFileContent(file: File): Promise<string | null> {
  if (!isTextFile(file) || file.size > MAX_INLINE_SIZE) {
    return Promise.resolve(null);
  }
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => {
      const text = reader.result as string;
      resolve(text || null);
    };
    reader.onerror = () => resolve(null);
    reader.readAsText(file);
  });
}
