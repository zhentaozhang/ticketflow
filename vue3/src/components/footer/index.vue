<template>
  <footer class="tf-footer">
    <div class="tf-footer__inner">
      <div class="tf-footer__main">
        <div class="tf-footer__brand">
          <img src="@/assets/login/logo.png" alt="TicketFlow" class="tf-footer__logo" />
          <p class="tf-footer__slogan">为您提供极致的购票体验</p>
        </div>
        <div class="tf-footer__links">
          <div class="link-group">
            <div class="link-group__title">帮助中心</div>
            <router-link :to="{ path: '/help', query: { tab: 'faq' } }">常见问题</router-link>
            <router-link :to="{ path: '/help', query: { tab: 'buy-guide' } }">如何购票</router-link>
            <router-link :to="{ path: '/help', query: { tab: 'refund-policy' } }">退换说明</router-link>
          </div>
          <div class="link-group">
            <div class="link-group__title">关于我们</div>
            <router-link :to="{ path: '/help', query: { tab: 'about' } }">公司介绍</router-link>
            <router-link :to="{ path: '/help', query: { tab: 'contact' } }">联系我们</router-link>
            <router-link :to="{ path: '/help', query: { tab: 'join' } }">加入我们</router-link>
          </div>
          <div class="link-group">
            <div class="link-group__title">服务条款</div>
            <router-link :to="{ path: '/help', query: { tab: 'user-agreement' } }">用户协议</router-link>
            <router-link :to="{ path: '/help', query: { tab: 'privacy-policy' } }">隐私政策</router-link>
          </div>
        </div>
      </div>
      <div class="tf-footer__bottom">
        <p>© 2026 TicketFlow. All Rights Reserved.</p>
        <p>营业执照 | 增值电信业务经营许可证 | 京ICP备12345678号</p>
      </div>
    </div>
  </footer>
</template>

<script setup lang="ts">
</script>

<style scoped lang="scss">
.tf-footer {
  background: var(--tf-surface);
  border-top: 1px solid var(--tf-border);
  padding: 60px 0 24px;
  margin-top: 40px;

  &__inner {
    max-width: var(--tf-max-width);
    margin: 0 auto;
    padding: 0 20px;
  }

  &__main {
    display: flex;
    justify-content: space-between;
    margin-bottom: 40px;
  }

  &__brand {
    max-width: 300px;
  }

  &__logo {
    height: 40px;
    margin-bottom: 16px;
    object-fit: contain;
  }

  &__slogan {
    font-size: 14px;
    color: var(--tf-text-secondary);
    line-height: 1.6;
  }

  &__links {
    display: flex;
    gap: 80px;

    .link-group {
      display: flex;
      flex-direction: column;
      gap: 12px;

      &__title {
        font-size: 16px;
        font-weight: 600;
        color: var(--tf-text-primary);
        margin-bottom: 8px;
      }

      a {
        font-size: 14px;
        color: var(--tf-text-secondary);
        transition: color 0.2s;
        text-decoration: none;

        &:hover {
          color: var(--tf-primary);
        }
      }
    }
  }

  &__bottom {
    padding-top: 24px;
    border-top: 1px solid var(--tf-border);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: #9CA3AF;
  }
}
</style>
