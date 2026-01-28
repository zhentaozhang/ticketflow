<template>
  <Header @updateValue="handleUpdate"></Header>
  <div class="app-container">
    <!-- 轮播图 (2026 高斯模糊环境光，图片 100% 完整展示不裁剪) -->
    <el-carousel height="440px" :interval="5000" arrow="always" class="carousel-lamp">
      <el-carousel-item v-for="item in picArr" :key="item">
        <div class="carousel-item-wrapper">
          <!-- 背景高斯模糊环境光 -->
          <img :src="item" alt="Background Blur" class="carousel-bg-blur" />
          <!-- 前景图片 100% 完整无死角展示 -->
          <img :src="item" alt="Banner" class="carousel-img" />
        </div>
      </el-carousel-item>
    </el-carousel>

    <!-- 分类导航 (2026 微光玻璃卡片) -->
    <div class="category">
      <ul>
        <li v-for="(item, ind) in categoryArr" :key="ind">
          <router-link
            :to="{ path: '/allType/index', query: { type: item.type, name: item.name, id: item.id } }"
            class="category-card"
          >
            <div class="category-card__icon" :class="'icon-bg--' + ((ind % 5) + 1)">
              <i :class="['sprit', 'sprit' + (ind + 1)]"></i>
            </div>
            <span class="category-card__name">{{ item.name }}</span>
          </router-link>
        </li>
      </ul>
    </div>

    <!-- 推荐内容区 -->
    <div class="diffrentType" v-for="(item, index) in programList" :key="item.categoryId">
      <div class="section-wrapper">
        <!-- 头部标题栏 -->
        <div class="name">
          <div class="title-left">
            <span class="title-accent-bar"></span>
            <span class="title-text">{{ item.categoryName }}</span>
            <span class="title-sub-tag">全网爆款精选</span>
          </div>
          <router-link
            :to="{ path: '/allType/index', query: { type: 1, name: item.categoryName, id: item.categoryId } }"
            class="more-pill"
          >
            查看全部 <el-icon><ArrowRight /></el-icon>
          </router-link>
        </div>

        <!-- 节目网格 -->
        <div class="box" v-if="item.programListVoList && item.programListVoList.length">
          <!-- 左侧主打推荐大卡片 -->
          <div class="box-left">
            <router-link
              :to="{ name: 'detail', params: { id: item.programListVoList[0].id } }"
              class="show-card show-card--large"
            >
              <img
                :src="item.programListVoList[0].itemPicture || defaultPoster"
                alt="Poster"
                @error="handleImgError"
              />
              <span class="rank-badge">TOP 1</span>
              <div class="show-card__overlay">
                <div class="show-card__tag">🔥 热门推荐</div>
                <div class="show-card__title">{{ item.programListVoList[0].title }}</div>
                <div class="show-card__meta">
                  <span>{{ item.programListVoList[0].place }}</span>
                </div>
                <div class="show-card__price-row">
                  <span class="price-symbol">￥</span>
                  <span class="price-val">{{ item.programListVoList[0].minPrice }}</span>
                  <span class="price-unit">起</span>
                </div>
              </div>
            </router-link>
          </div>

          <!-- 右侧小卡片列表 Grid (3 列，严格包含在容器边框内) -->
          <div class="box-right">
            <router-link
              v-for="dict in item.programListVoList.slice(1)"
              :key="dict.id"
              :to="{ name: 'detail', params: { id: dict.id } }"
              class="show-card show-card--small"
            >
              <div class="show-card__thumb">
                <img
                  :src="dict.itemPicture || defaultPoster"
                  alt="Poster"
                  @error="handleImgError"
                />
              </div>
              <div class="show-card__info">
                <div class="show-card__info-title">{{ dict.title }}</div>
                <div class="show-card__info-meta">
                  <el-icon><Location /></el-icon>
                  <span>{{ dict.place }}</span>
                </div>
                <div class="show-card__info-meta">
                  <el-icon><Calendar /></el-icon>
                  <span>{{ dict.showTime }} {{ dict.showWeekTime }}</span>
                </div>
                <div class="show-card__info-bottom">
                  <div class="show-card__info-price">
                    ￥<strong>{{ dict.minPrice }}</strong> <span>起</span>
                  </div>
                  <span class="buy-pill">抢票 &rarr;</span>
                </div>
              </div>
            </router-link>
          </div>
        </div>
      </div>
    </div>

    <Footer></Footer>
  </div>
</template>

<script setup lang="ts">
import Header from '@/components/header/index.vue'
import swiperPic1 from '@/assets/section/ticketFlow.png'
import defaultPoster from '@/assets/section/ticketFlow.png'
import { onMounted, ref } from 'vue'
import Footer from '@/components/footer/index'
import { getcategoryType, getMainCategory } from '@/api/index'
import { ArrowRight, Location, Calendar } from '@element-plus/icons-vue'

// 轮播图
const picArr = [swiperPic1]

const categoryArr = ref<any[]>([])
const programList = ref<any[]>([])
const queryParams = ref({
  areaId: 0,
  parentProgramCategoryIds: [] as any[],
})

onMounted(() => {
  getgetcategoryList()
})

function handleImgError(e: Event) {
  const target = e.target as HTMLImageElement
  if (target) {
    target.src = defaultPoster
  }
}

function getgetcategoryList() {
  getcategoryType({ type: 1 }).then((response: any) => {
    categoryArr.value = response.data || []
    getMainCategoryList()
  })
}

function handleUpdate(value: any) {
  queryParams.value.areaId = value
  if (categoryArr.value.length > 0) {
    getMainCategoryList()
  }
}

function getMainCategoryList() {
  queryParams.value.parentProgramCategoryIds = []
  for (let i = 0; i < 4 && i < categoryArr.value.length; i++) {
    queryParams.value.parentProgramCategoryIds.push(categoryArr.value[i].id)
  }
  getMainCategory(queryParams.value).then((response: any) => {
    programList.value = response.data || []
  })
}
</script>

<style scoped lang="scss">
.app-container {
  max-width: var(--tf-max-width, 1200px);
  width: 100%;
  margin: 0 auto;
  padding: 0 20px 40px;
  box-sizing: border-box;
  overflow-x: hidden;

  /* 轮播图 (高斯模糊背景环境光 + 100% 完整展示) */
  .carousel-lamp {
    width: 100%;
    margin-top: 20px;
    border-radius: var(--tf-radius-lg);
    overflow: hidden;
    box-shadow: var(--tf-shadow-md);

    .carousel-item-wrapper {
      position: relative;
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      background: #0f172a;
    }

    .carousel-bg-blur {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
      filter: blur(28px) brightness(0.65);
      transform: scale(1.15);
      user-select: none;
      pointer-events: none;
    }

    .carousel-img {
      position: relative;
      z-index: 2;
      width: 100%;
      height: 100%;
      object-fit: contain;
      display: block;
    }
  }

  .carousel-lamp :deep(.el-carousel__container) {
    height: 440px;
  }

  /* ── 分类导航 ── */
  .category {
    margin-top: 24px;
    padding: 20px 24px;
    background: var(--tf-surface);
    border-radius: var(--tf-radius-lg);
    box-shadow: var(--tf-shadow-sm);
    border: 1px solid var(--tf-border);
    box-sizing: border-box;
    width: 100%;

    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(10, 1fr);
      gap: 10px;
    }

    li {
      text-align: center;
    }
  }

  .category-card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 10px 4px;
    border-radius: var(--tf-radius-md);
    transition: all 0.25s;
    cursor: pointer;
    text-decoration: none;

    &:hover {
      background: var(--tf-primary-light);
      transform: translateY(-3px);
      box-shadow: var(--tf-shadow-sm);

      .category-card__name {
        color: var(--tf-primary);
        font-weight: 700;
      }
    }

    &__icon {
      width: 54px;
      height: 54px;
      border-radius: 16px;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: transform 0.25s;
    }

    &__name {
      font-size: 12px;
      color: var(--tf-text-primary);
      font-weight: 500;
      transition: color 0.2s;
      white-space: nowrap;
    }
  }

  /* 渐变图标圈底色 */
  .icon-bg--1 { background: #EFF6FF; }
  .icon-bg--2 { background: #FEF2F2; }
  .icon-bg--3 { background: #F0FDF4; }
  .icon-bg--4 { background: #FFF7ED; }
  .icon-bg--5 { background: #F5F3FF; }

  /* 精灵图 (精确对齐原始 48px 像素采样) */
  .sprit {
    display: block;
    width: 48px;
    height: 48px;
    background: url("/src/assets/section/sprit.png") no-repeat;
    background-size: 100% auto;
  }
  .sprit1  { background-position: 0 0; }
  .sprit2  { background-position: 0 -64px; }
  .sprit3  { background-position: 0 -120px; }
  .sprit4  { background-position: 0 -180px; }
  .sprit5  { background-position: 0 -240px; }
  .sprit6  { background-position: 0 -297px; }
  .sprit7  { background-position: 0 -360px; }
  .sprit8  { background-position: 0 -420px; }
  .sprit9  { background-position: 0 -480px; }
  .sprit10 { background-position: 0 -540px; }

  /* ── 推荐内容板块 ── */
  .diffrentType {
    margin-top: 24px;
    padding: 24px;
    background: var(--tf-surface);
    border-radius: var(--tf-radius-lg);
    border: 1px solid var(--tf-border);
    box-shadow: var(--tf-shadow-sm);
    box-sizing: border-box;
    width: 100%;
    overflow: hidden;

    .section-wrapper {
      width: 100%;
      box-sizing: border-box;
    }

    .name {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 18px;
      width: 100%;
      box-sizing: border-box;

      .title-left {
        display: flex;
        align-items: center;
        gap: 10px;

        .title-accent-bar {
          width: 4px;
          height: 20px;
          background: var(--tf-primary);
          border-radius: 2px;
        }

        .title-text {
          font-size: 20px;
          font-weight: 700;
          color: var(--tf-text-primary);
        }

        .title-sub-tag {
          font-size: 12px;
          color: var(--tf-primary);
          background: var(--tf-primary-light);
          padding: 2px 8px;
          border-radius: var(--tf-radius-full);
          font-weight: 600;
        }
      }

      .more-pill {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 13px;
        font-weight: 600;
        color: var(--tf-text-secondary);
        padding: 4px 12px;
        border-radius: var(--tf-radius-full);
        transition: all 0.2s;
        text-decoration: none;

        &:hover {
          color: var(--tf-primary);
          background: var(--tf-primary-light);
        }
      }
    }
  }

  .box {
    display: grid;
    grid-template-columns: 210px minmax(0, 1fr);
    gap: 16px;
    width: 100%;
    box-sizing: border-box;
  }

  .box-left {
    width: 210px;
    flex-shrink: 0;
  }

  /* 左侧主打大卡片 */
  .show-card--large {
    position: relative;
    display: block;
    width: 210px;
    height: 100%;
    min-height: 360px;
    border-radius: var(--tf-radius-md);
    overflow: hidden;
    text-decoration: none;
    box-shadow: var(--tf-shadow-sm);
    border: 1px solid var(--tf-border);
    transition: all 0.25s;
    box-sizing: border-box;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
      transition: transform 0.3s;
    }

    .rank-badge {
      position: absolute;
      top: 10px;
      left: 10px;
      padding: 3px 8px;
      background: rgba(15, 23, 42, 0.85);
      color: #ffd700;
      font-size: 11px;
      font-weight: 800;
      border-radius: var(--tf-radius-sm);
      backdrop-filter: blur(8px);
      box-shadow: 0 2px 8px rgba(0,0,0,0.2);
    }

    .show-card__overlay {
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      padding: 16px 14px 14px;
      background: linear-gradient(180deg, rgba(0,0,0,0) 0%, rgba(15, 23, 42, 0.92) 100%);
      color: #ffffff;
      display: flex;
      flex-direction: column;
      gap: 4px;

      .show-card__tag {
        font-size: 11px;
        font-weight: 700;
        color: #ff8a7a;
      }

      .show-card__title {
        font-size: 14px;
        font-weight: 700;
        line-height: 1.4;
        overflow: hidden;
        display: -webkit-box;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
      }

      .show-card__meta {
        font-size: 12px;
        color: rgba(255, 255, 255, 0.8);
      }

      .show-card__price-row {
        margin-top: 2px;
        display: flex;
        align-items: baseline;
        color: #ffd700;

        .price-symbol { font-size: 13px; font-weight: 700; }
        .price-val { font-size: 20px; font-weight: 800; }
        .price-unit { font-size: 12px; color: rgba(255, 255, 255, 0.8); margin-left: 2px; }
      }
    }

    &:hover {
      transform: translateY(-3px);
      box-shadow: var(--tf-shadow-lg);

      img {
        transform: scale(1.04);
      }
    }
  }

  /* 右侧小卡片网格 (严格受控在父卡片边框内) */
  .box-right {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    width: 100%;
    min-width: 0;
    box-sizing: border-box;
  }

  .show-card--small {
    display: flex;
    gap: 8px;
    padding: 8px;
    background: var(--tf-surface);
    border: 1px solid var(--tf-border);
    border-radius: var(--tf-radius-md);
    text-decoration: none;
    color: inherit;
    transition: all 0.25s;
    box-sizing: border-box;
    min-width: 0;
    overflow: hidden;

    &:hover {
      transform: translateY(-2px);
      box-shadow: var(--tf-shadow-md);
      border-color: var(--tf-primary);

      .buy-pill {
        background: var(--tf-primary);
        color: #ffffff;
      }
    }

    .show-card__thumb {
      flex-shrink: 0;
      width: 78px;
      height: 106px;
      border-radius: var(--tf-radius-sm);
      overflow: hidden;
      border: 1px solid var(--tf-border);

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        display: block;
      }
    }

    .show-card__info {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      gap: 3px;

      &-title {
        font-size: 12px;
        font-weight: 600;
        color: var(--tf-text-primary);
        line-height: 1.35;
        overflow: hidden;
        display: -webkit-box;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
      }

      &-meta {
        font-size: 11px;
        color: var(--tf-text-secondary);
        display: flex;
        align-items: center;
        gap: 3px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;

        .el-icon {
          font-size: 11px;
          color: var(--tf-text-muted);
          flex-shrink: 0;
        }

        span {
          overflow: hidden;
          text-overflow: ellipsis;
        }
      }

      &-bottom {
        margin-top: auto;
        display: flex;
        align-items: center;
        justify-content: space-between;
      }

      &-price {
        font-size: 11px;
        color: var(--tf-primary);

        strong {
          font-size: 16px;
          font-weight: 800;
        }

        span {
          font-size: 11px;
          color: var(--tf-text-muted);
        }
      }

      .buy-pill {
        font-size: 10px;
        font-weight: 700;
        color: var(--tf-primary);
        background: var(--tf-primary-light);
        padding: 2px 8px;
        border-radius: var(--tf-radius-full);
        transition: all 0.2s;
        white-space: nowrap;
      }
    }
  }
}
</style>
