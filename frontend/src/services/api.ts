export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public body: Record<string, unknown>,
  ) {
    super(message);
  }
}
export async function api<T = Record<string, unknown>>(
  path: string,
  method = "GET",
  data?: unknown,
): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(
    () => controller.abort(),
    path === "/ai/parse"
      ? 150000
      : path.startsWith("/knowledge/") || path.endsWith("/test")
        ? 90000
        : 20000,
  );
  try {
    const response = await fetch("/api" + path, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers:
        method === "GET" ? undefined : { "Content-Type": "application/json" },
      body: method === "GET" ? undefined : JSON.stringify(data ?? {}),
    });
    const result = await response.json();
    if (!response.ok)
      throw new ApiError(result.error ?? "请求失败", response.status, result);
    return result as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (error instanceof Error && error.name === "AbortError")
      throw new Error("请求超时，请同步查看操作是否已完成后再试。");
    throw new Error("连接失败，请检查服务是否运行。");
  } finally {
    clearTimeout(timeout);
  }
}

export async function upload<T>(
  path: string,
  file: File,
  metadata: unknown,
): Promise<T> {
  const body = new FormData();
  body.append("file", file);
  body.append("metadata", JSON.stringify(metadata));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 60000);
  try {
    const response = await fetch("/api" + path, {
      method: "POST",
      credentials: "same-origin",
      body,
      signal: controller.signal,
    });
    const result = await response.json();
    if (!response.ok)
      throw new ApiError(result.error ?? "上传失败", response.status, result);
    return result as T;
  } catch (e) {
    if (e instanceof ApiError) throw e;
    throw new Error("上传未完成，请刷新资料列表确认后重试。");
  } finally {
    clearTimeout(timer);
  }
}
