// Augment Math to include the easing function
declare global {
  interface Math {
    easeInOutQuad(t: number, b: number, c: number, d: number): number
  }
}

Math.easeInOutQuad = function (t: number, b: number, c: number, d: number): number {
  t /= d / 2
  if (t < 1) {
    return (c / 2) * t * t + b
  }
  t--
  return (-c / 2) * (t * (t - 2) - 1) + b
}

// requestAnimationFrame for Smart Animating
const requestAnimFrame: (callback: FrameRequestCallback) => number = (
  window.requestAnimationFrame ||
  (window as any).webkitRequestAnimationFrame ||
  (window as any).mozRequestAnimationFrame ||
  function (callback: FrameRequestCallback) {
    return window.setTimeout(callback, 1000 / 60)
  }
).bind(window)

function move(amount: number): void {
  document.documentElement.scrollTop = amount;
  (document.body.parentNode as HTMLElement).scrollTop = amount
  document.body.scrollTop = amount
}

function position(): number {
  return (
    document.documentElement.scrollTop ||
    (document.body.parentNode as HTMLElement).scrollTop ||
    document.body.scrollTop
  )
}

/**
 * @param to - 目标位置
 * @param duration - 动画时长（ms），默认 500
 * @param callback - 滚动完成后的回调
 */
export function scrollTo(to: number, duration = 500, callback?: () => void): void {
  const start = position()
  const change = to - start
  const increment = 20
  let currentTime = 0

  const animateScroll = function () {
    currentTime += increment
    const val = Math.easeInOutQuad(currentTime, start, change, duration)
    move(val)
    if (currentTime < duration) {
      requestAnimFrame(animateScroll)
    } else {
      callback?.()
    }
  }
  animateScroll()
}
