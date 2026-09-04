// shadcn 风格 button（手写，CVA 语义换平铺变体表——项目不引入 class-variance-authority）。

import * as React from "react";
import { cn } from "@/lib/utils";

type Variant = "default" | "secondary" | "outline" | "ghost" | "destructive" | "link";
type Size = "default" | "sm" | "lg" | "icon";

const variantClass: Record<Variant, string> = {
  default:
    "bg-primary text-primary-foreground shadow-warm hover:bg-primary/90",
  secondary:
    "bg-secondary text-secondary-foreground shadow-warm hover:bg-secondary/80",
  outline:
    "border border-border bg-card shadow-warm hover:bg-accent hover:text-accent-foreground",
  ghost: "hover:bg-accent hover:text-accent-foreground",
  destructive:
    "bg-destructive text-white shadow-warm hover:bg-destructive/90",
  link: "text-primary underline-offset-4 hover:underline",
};

const sizeClass: Record<Size, string> = {
  default: "h-9 px-4 py-2 has-[>svg]:px-3",
  sm: "h-8 rounded-md gap-1.5 px-3 has-[>svg]:px-2.5",
  lg: "h-10 rounded-md px-6 has-[>svg]:px-4",
  icon: "size-9",
};

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
}

export function Button({
  className,
  variant = "default",
  size = "default",
  type = "button",
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-[10px] text-sm font-medium transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0",
        variantClass[variant],
        sizeClass[size],
        className,
      )}
      {...props}
    />
  );
}
