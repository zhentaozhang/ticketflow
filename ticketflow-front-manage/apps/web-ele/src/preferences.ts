import { defineOverridesPreferences } from '@vben/preferences';

/**
 * @description 项目配置文件
 * 只需要覆盖项目中的一部分配置，不需要的配置不用覆盖，会自动使用默认配置
 * !!! 更改配置后请清空缓存，否则可能不生效
 */
export const overridesPreferences = defineOverridesPreferences({
  // overrides
  app: {
    name: "TicketFlow",
    enableCheckUpdates: false,
    layout: "header-sidebar-nav",
    authPageLayout: "panel-center",
    //动态标题
    dynamicTitle: true
  },
  shortcutKeys: {
    enable: false
  },
  theme: {
    mode: "light",
    //顶部不使用暗黑模式
    semiDarkHeader: false,
    //主题色
    builtinType: "violet",
    //偏好色
    colorPrimary: "hsl(245 82% 67%)",
    //圆度
    radius: "1"
  },
  transition: {
    //滑动动画
    name: "fade-up"
  },
  widget: {
    languageToggle: false,
    notification: false
  }
});
