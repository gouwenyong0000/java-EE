<template>
  <div id="aCoursesList" class="bg-fa of">
    <!-- /课程列表 开始 -->
    <section class="container">
      <header class="comm-title">
        <h2 class="fl tac">
          <span class="c-333">全部课程</span>
        </h2>
      </header>
      <section class="c-sort-box">
        <section class="c-s-dl">
          <dl>
            <dt>
              <span class="c-999 fsize14">课程类别</span>
            </dt>
            <dd class="c-s-dl-li">
              <ul class="clearfix">
                <li>
                  <a title="全部" href="#">全部</a>
                </li>
                <li
                  v-for="(item, index) in subjectNestedList"
                  :key="index"
                  :class="{ active: index == oneIndex }"
                >
                  <a
                    :title="item.label"
                    href="#"
                    @click="searchOne(item.id, index)"
                    >{{ item.label }}</a
                  >
                </li>
              </ul>
            </dd>
          </dl>
          <dl>
            <dt>
              <span class="c-999 fsize14"></span>
            </dt>
            <dd class="c-s-dl-li">
              <ul class="clearfix">
                <li
                  v-for="(item, index) in subSubjectList"
                  :key="index"
                  :class="{ active: twoIndex == index }"
                >
                  <a
                    :title="item.label"
                    href="#"
                    @click="searchTwo(item.id, index)"
                    >{{ item.label }}</a
                  >
                </li>
              </ul>
            </dd>
          </dl>
          <div class="clear"></div>
        </section>
        <div class="js-wrap">
          <section class="fr">
            <span class="c-ccc">
              <i class="c-master f-fM">1</i>/
              <i class="c-666 f-fM">1</i>
            </span>
          </section>
          <section class="fl">
            <ol class="js-tap clearfix">
              <li :class="{ 'current bg-orange': buyCountSort != '' }">
                <a
                  title="销量"
                  href="javascript:void(0);"
                  @click="searchBuyCount()"
                  >销量
                  <span :class="{ hide: buyCountSort == '' }">↓</span>
                </a>
              </li>
              <li :class="{ 'current bg-orange': gmtCreateSort != '' }">
                <a
                  title="最新"
                  href="javascript:void(0);"
                  @click="searchGmtCreate()"
                  >最新
                  <span :class="{ hide: gmtCreateSort == '' }">↓</span>
                </a>
              </li>
              <li :class="{ 'current bg-orange': priceSort != '' }">
                <a
                  title="价格"
                  href="javascript:void(0);"
                  @click="searchPrice()"
                  >价格&nbsp;
                  <span :class="{ hide: priceSort == '' }">↓</span>
                </a>
              </li>
            </ol>
          </section>
        </div>
        <div class="mt40">
          <!-- /无数据提示 开始-->
          <section class="no-data-wrap" v-if="data.total == 0">
            <em class="icon30 no-data-ico">&nbsp;</em>
            <span class="c-666 fsize14 ml10 vam"
              >没有相关数据，小编正在努力整理中...</span
            >
          </section>
          <!-- /无数据提示 结束-->
          <!-- 数据列表 开始-->
          <article v-if="data.total > 0" class="comm-course-list">
            <ul id="bna" class="of">
              <li v-for="item in data.items" :key="item.id">
                <div class="cc-l-wrap">
                  <section class="course-img">
                    <img
                      :src="item.cover"
                      class="img-responsive"
                      alt="听力口语"
                    />
                    <div class="cc-mask">
                      <a
                        :href="'/course/' + item.id"
                        title="开始学习"
                        class="comm-btn c-btn-1"
                        >开始学习</a
                      >
                    </div>
                  </section>
                  <h3 class="hLh30 txtOf mt10">
                    <a
                      :href="'/course/' + item.id"
                      :title="item.title"
                      class="course-title fsize18 c-333"
                      >{{ item.title }}</a
                    >
                  </h3>
                  <section class="mt10 hLh20 of">
                    <span
                      v-if="Number(item.price) === 0"
                      class="fr jgTag bg-green"
                    >
                      <i class="c-fff fsize12 f-fA">免费</i>
                    </span>
                    <span class="fl jgAttr c-ccc f-fA">
                      <i class="c-999 f-fA">{{ item.viewCount }}人学习</i>
                      |
                      <i class="c-999 f-fA">9634评论</i>
                    </span>
                  </section>
                </div>
              </li>
            </ul>
            <div class="clear" />
          </article>
          <!-- /数据列表 结束-->
        </div>
        <!-- 公共分页 开始 -->
        <div>
          <div>
            <div class="paging">
              <!-- undisable这个class是否存在，取决于数据属性hasPrevious -->
              <a
                :class="{ undisable: !data.hasPrevious }"
                href="#"
                title="首页"
                @click.prevent="gotoPage(1)"
                >首</a
              >
              <a
                :class="{ undisable: !data.hasPrevious }"
                href="#"
                title="前一页"
                @click.prevent="gotoPage(data.current - 1)"
                >&lt;</a
              >
              <a
                v-for="page in data.pages"
                :key="page"
                :class="{
                  current: data.current == page,
                  undisable: data.current == page,
                }"
                :title="'第' + page + '页'"
                href="#"
                @click.prevent="gotoPage(page)"
                >{{ page }}</a
              >
              <a
                :class="{ undisable: !data.hasNext }"
                href="#"
                title="后一页"
                @click.prevent="gotoPage(data.current + 1)"
                >&gt;</a
              >
              <a
                :class="{ undisable: !data.hasNext }"
                href="#"
                title="末页"
                @click.prevent="gotoPage(data.pages)"
                >末</a
              >
              <div class="clear" />
            </div>
          </div>
        </div>
        <!-- 公共分页 结束 -->
      </section>
    </section>
    <!-- /课程列表 结束 -->
  </div>
</template>
<script>
import course from "@/api/course";

export default {
  data() {
    return {
      page: 1, //当前页
      data: {}, //课程列表的值
      subjectNestedList: [], // 一级分类列表
      subSubjectList: [], // 二级分类列表
      searchObj: {}, // 查询表单对象
      oneIndex: -1, //控制一级分类选中效果  索引等于该值表示选中
      twoIndex: -1,
      buyCountSort: "",
      gmtCreateSort: "",
      priceSort: "",
    };
  },
  created() {
    this.getSubject();
    this.initFirstCourseList();
  },
  methods: {
    //1、查询一级
    getSubject() {
      course.getAllSubject().then((response) => {
        this.subjectNestedList = response.data.data.getTreeList;
      });
    },
    //2、查询第一页数据
    initFirstCourseList() {
      course.getPageList(1, 8, this.searchObj).then((response) => {
        this.data = response.data.data;
      });
    },
    //3、分页切换
    gotoPage(page) {
      course.getPageList(page, 8, this.searchObj).then((response) => {
        this.data = response.data.data;
      });
    },
    //点击一级分类  显示二级分类
    searchOne(subjectParentId, index) {
      this.oneIndex = index;
      this.twoIndex = -1;
      this.searchObj.subjectId = "";
      this.subSubjectList = [];
      this.searchObj.subjectParentId = subjectParentId; //赋值searchObj.subjectParentId进行条件查询
      this.gotoPage(1);

      for (let index = 0; index < this.subjectNestedList.length; index++) {
        const element = this.subjectNestedList[index];
        if (element.id == subjectParentId) {
          this.subSubjectList = element.children;
        }
      }
    },
    searchTwo(subjectId, index) {
      this.twoIndex = index;
      this.searchObj.subjectId = subjectId;
      this.gotoPage(1);
    },
    //购买量查询排序
    searchBuyCount() {
      this.buyCountSort = "1";
      this.gmtCreateSort = "";
      this.priceSort = "";
      this.searchObj.buyCountSort = this.buyCountSort;
      this.searchObj.gmtCreateSort = this.gmtCreateSort;
      this.searchObj.priceSort = this.priceSort;
      this.gotoPage(this.page)
    },
    //更新时间查询排序
    searchGmtCreate() {
      this.buyCountSort = "";
      this.gmtCreateSort = "1";
      this.priceSort = "";
      this.searchObj.buyCountSort = this.buyCountSort;
      this.searchObj.gmtCreateSort = this.gmtCreateSort;
      this.searchObj.priceSort = this.priceSort;
      this.gotoPage(this.page)
    },
    //价格查询排序
    searchPrice() {
      this.buyCountSort = "";
      this.gmtCreateSort = "";
      this.priceSort = "1";
      this.searchObj.buyCountSort = this.buyCountSort;
      this.searchObj.gmtCreateSort = this.gmtCreateSort;
      this.searchObj.priceSort = this.priceSort;
      this.gotoPage(this.page)
    },

  },
};
</script>

<style scoped>
.active {
  background: #bdbdbd;
}
.hide {
  display: none;
}
.show {
  display: block;
}
</style>


