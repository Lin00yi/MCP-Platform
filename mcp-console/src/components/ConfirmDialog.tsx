import { useEffect, useState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** 通用二次确认弹窗（危险操作确认）。传入 confirmValue 时，需输入该值才能确认。 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmText = "确认",
  loading = false,
  onConfirm,
  confirmValue,
  confirmValueLabel,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmText?: string;
  loading?: boolean;
  onConfirm: () => void;
  /** 需要用户手动输入、完全匹配后才能确认的值（如应用 ID / Server 名称） */
  confirmValue?: string;
  /** 输入框提示文案 */
  confirmValueLabel?: string;
}) {
  const [input, setInput] = useState("");
  const needInput = confirmValue !== undefined && confirmValue !== "";
  const canConfirm = !needInput || input.trim() === confirmValue;

  useEffect(() => {
    if (open) {
      setInput("");
    }
  }, [open]);

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {needInput && (
          <div className="space-y-1.5">
            <Label className="text-sm">{confirmValueLabel ?? "请输入确认值"}</Label>
            <Input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={confirmValue}
              className="font-mono"
              autoFocus
            />
          </div>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={loading}>取消</AlertDialogCancel>
          <AlertDialogAction
            className="bg-destructive text-white hover:bg-destructive/90"
            disabled={loading || !canConfirm}
            onClick={onConfirm}
          >
            {loading ? "处理中…" : confirmText}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
