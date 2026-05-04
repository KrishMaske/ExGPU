import Image from "next/image";
import { cx } from "./ui";

/**
 * ExGPU brand mark.
 *
 * <p>Renders `exgpu-mark.png`, which is the original `exgpu_logo.png` with its transparent
 * padding trimmed off. The source art sits in the middle of a 500×500 canvas and only fills
 * about half of it, so drawing it directly produced a mark that looked tiny and off-centre.
 * The previous fix — a fixed-size box with `overflow-hidden` and `scale-[1.28]` — cropped the
 * padding at one size but distorted the framing at any other, because the artwork is 1.13:1
 * rather than square. Trimming the asset removes the need for any of that: the image is now
 * drawn at its true aspect ratio with no cropping.
 *
 * <p>The dark-mode filter exists because the mark is a deep violet (#5829D9) that was chosen
 * against white. On the near-black dark surface it reads as muddy, so it is lifted rather than
 * recoloured — a flat recolour would throw away the gradient the mark is built from.
 */
export function Logo({
  className,
  showWord = true,
  priority = false,
}: {
  className?: string;
  showWord?: boolean;
  priority?: boolean;
}) {
  return (
    <span
      className={cx("inline-flex min-w-0 items-center gap-2.5", className)}
      aria-label={showWord ? undefined : "ExGPU"}
    >
      <Image
        src="/exgpu-mark.png"
        alt=""
        width={292}
        height={258}
        priority={priority}
        aria-hidden
        className="h-8 w-auto shrink-0 [filter:none] dark:[filter:brightness(1.45)_saturate(1.15)]"
      />
      {showWord && (
        <span className="truncate text-xl font-semibold tracking-[-0.035em] text-ink">
          ExGPU
        </span>
      )}
    </span>
  );
}
