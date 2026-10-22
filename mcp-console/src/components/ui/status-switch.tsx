import * as React from "react";
import * as SwitchPrimitives from "@radix-ui/react-switch";
import { cn } from "@/lib/utils";

export interface StatusSwitchProps
  extends Omit<React.ComponentPropsWithoutRef<typeof SwitchPrimitives.Root>, "children"> {
  /** 开启时显示在开关内的文案 */
  onLabel?: string;
  /** 关闭时显示在开关内的文案 */
  offLabel?: string;
}

/**
 * 带内联状态文案的开关：状态文字直接写在轨道里，避免在开关右侧再挂一个「启用/禁用」标签。
 * 文案始终位于滑块的对侧，不会被滑块遮住。
 */
const StatusSwitch = React.forwardRef<
  React.ElementRef<typeof SwitchPrimitives.Root>,
  StatusSwitchProps
>(({ className, checked, onLabel = "启用", offLabel = "禁用", ...props }, ref) => (
  <SwitchPrimitives.Root
    ref={ref}
    checked={checked}
    className={cn(
      "group relative inline-flex h-6 w-14 shrink-0 cursor-pointer items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:bg-primary data-[state=unchecked]:bg-input",
      className,
    )}
    {...props}
  >
    <span
      className={cn(
        "pointer-events-none absolute inset-0 flex items-center text-[10px] font-medium leading-none",
        checked ? "justify-start pl-2 text-primary-foreground" : "justify-end pr-2 text-muted-foreground",
      )}
    >
      {checked ? onLabel : offLabel}
    </span>
    <SwitchPrimitives.Thumb className="pointer-events-none block h-5 w-5 rounded-full bg-white shadow-md ring-1 ring-black/5 transition-transform data-[state=checked]:translate-x-[34px] data-[state=unchecked]:translate-x-[2px]" />
  </SwitchPrimitives.Root>
));
StatusSwitch.displayName = "StatusSwitch";

export { StatusSwitch };
