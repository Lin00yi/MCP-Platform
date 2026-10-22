import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * 表格「操作」列的按钮组：垂直堆叠，宽度贴合内容，避免把表格撑宽、把按钮挤成一团。
 * 行内按钮请统一使用 Button 的 size="xs"（带文字）或 size="iconSm"（纯图标）。
 */
const RowActions = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn("flex w-fit flex-col items-stretch gap-0.5", className)}
      {...props}
    />
  ),
);
RowActions.displayName = "RowActions";

export { RowActions };
