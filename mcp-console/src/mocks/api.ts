/**
 * 本地 mock 已下线。
 *
 * <p>2026-10 删除应用维度后，旧的 mock 数据仍按 appId/appSecret/授权模型构造，
 * 与真实后端完全不一致，继续使用会误导联调。这里保留一个显式失败的占位对象：
 * 一旦误设 {@code VITE_USE_MOCK=true}，调用会立即报错并提示关闭 mock。
 */
export const mockApi: Record<string, (...args: unknown[]) => never> = new Proxy(
  {},
  {
    get: (_target, prop) => () => {
      throw new Error(
        `本地 mock 已下线（${String(prop)}）：请设置 VITE_USE_MOCK=false，连接真实网关。`,
      );
    },
  },
);
