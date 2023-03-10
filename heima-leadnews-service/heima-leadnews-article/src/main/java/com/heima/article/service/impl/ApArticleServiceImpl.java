package com.heima.article.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.article.mapper.ApArticleConfigMapper;
import com.heima.article.mapper.ApArticleContentMapper;
import com.heima.article.mapper.ApArticleMapper;
import com.heima.article.service.ApArticleService;
import com.heima.article.service.ArticleFreemarkerService;
import com.heima.common.constants.ArticleConstants;
import com.heima.common.redis.CacheService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.dtos.ArticleInfoDto;
import com.heima.model.article.dtos.UpdateArticleDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleConfig;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.vos.HotArticleVo;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.mess.ArticleVisitStreamMess;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class ApArticleServiceImpl extends ServiceImpl<ApArticleMapper, ApArticle> implements ApArticleService {
    @Autowired
    private ApArticleMapper apArticleMapper;
    @Autowired
    private ApArticleConfigMapper apArticleConfigMapper;

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    @Autowired
    private ArticleFreemarkerService articleFreemarkerService;

    @Autowired
    private CacheService cacheService;

    private static final short MAX_PAGE_SIZE = 50;

    @Override
    public ResponseResult load(Short loadtype, ArticleHomeDto dto) {
        //1.????????????
        //??????size
        Integer size = dto.getSize();
        if (size == null || size == 0) {
            size = 10;
        }
        size = Math.min(size, MAX_PAGE_SIZE);
        dto.setSize(size);
        //??????loadtype
        if (!loadtype.equals(ArticleConstants.LOADTYPE_LOAD_MORE) || loadtype.equals(ArticleConstants.LOADTYPE_LOAD_NEW)) {
            loadtype = ArticleConstants.LOADTYPE_LOAD_MORE;
        }
        //??????????????????
        if (StringUtils.isEmpty(dto.getTag())) {
            dto.setTag(ArticleConstants.DEFAULT_TAG);
        }
        //????????????
        if (dto.getMaxBehotTime() == null) {
            dto.setMaxBehotTime(new Date());
        }
        if (dto.getMinBehotTime() == null) {
            dto.setMinBehotTime(new Date());
        }
        //2????????????
        List<ApArticle> apArticleList = apArticleMapper.loadArticleList(dto, loadtype);
        return ResponseResult.okResult(apArticleList);
    }

    /**
     * ??????app???????????????
     *
     * @param dto
     * @return
     */
    @Override
    public ResponseResult saveArticle(ArticleDto dto) {
        //1.????????????
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApArticle apArticle = new ApArticle();
        BeanUtils.copyProperties(dto, apArticle);

        //2.??????????????????id
        if (dto.getId() == null) {
            //2.1 ?????????id  ??????  ??????  ????????????  ????????????
            //????????????
            //INSERT INTO ap_article ( id, title, layout, images, labels, publish_time ) VALUES ( ?, ?, ?, ?, ?, ? )
            save(apArticle);
            //????????????
            ApArticleConfig apArticleConfig = new ApArticleConfig(apArticle.getId());
            //INSERT INTO ap_article_config ( id, article_id, is_comment, is_forward, is_down, is_delete ) VALUES ( ?, ?, ?, ?, ?, ? )
            apArticleConfigMapper.insert(apArticleConfig);
            //?????? ????????????
            ApArticleContent apArticleContent = new ApArticleContent();
            apArticleContent.setArticleId(apArticle.getId());
            apArticleContent.setContent(dto.getContent());
            //INSERT INTO ap_article_content ( id, article_id, content ) VALUES ( ?, ?, ? )
            apArticleContentMapper.insert(apArticleContent);
        } else {
            //2.2 ??????id   ??????  ??????  ????????????
            //??????  ??????
            updateById(apArticle);
            //??????????????????
            //SELECT id,article_id,content FROM ap_article_content WHERE (article_id = ?)
            ApArticleContent apArticleContent =
                    apArticleContentMapper.selectOne(Wrappers.<ApArticleContent>lambdaQuery().eq(ApArticleContent::getArticleId, dto.getId()));
            apArticleContent.setContent(dto.getContent());
            //UPDATE ap_article_content SET article_id=?, content=? WHERE id=?
            apArticleContentMapper.updateById(apArticleContent);
        }
        //???????????? ???????????????????????????minio???
        articleFreemarkerService.buildArticleToMinIO(apArticle, dto.getContent());

        //3.????????????  ?????????id
        return ResponseResult.okResult(apArticle.getId());
    }

    @Override
    public ResponseResult loadArticleBehavior(ArticleInfoDto dto) {

        //0.????????????
        if (dto == null || dto.getArticleId() == null || dto.getAuthorId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        //{ "isfollow": true, "islike": true,"isunlike": false,"iscollection": true }
        boolean isfollow = false, islike = false, isunlike = false, iscollection = false;

        ApUser user = AppThreadLocalUtil.getUser();
        if (user != null) {
            //????????????
            String likeBehaviorJson = (String) cacheService.hGet("LIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString());
            if (StringUtils.isNotBlank(likeBehaviorJson)) {
                islike = true;
            }
            //??????????????????
            String unLikeBehaviorJson = (String) cacheService.hGet("UNLIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString());
            if (StringUtils.isNotBlank(unLikeBehaviorJson)) {
                isunlike = true;
            }
            //????????????
            String collctionJson = (String) cacheService.hGet("COLLECTION-BEHAVIOR-" + dto.getArticleId(), user.getId().toString());
            if (StringUtils.isNotBlank(collctionJson)) {
                iscollection = true;
            }

            //????????????
            Double score = cacheService.zScore("apuser:follow:" + user.getId(), dto.getAuthorId().toString());
            System.out.println(score);
            if (score != null) {
                isfollow = true;
            }

        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("isfollow", isfollow);
        resultMap.put("islike", islike);
        resultMap.put("isunlike", isunlike);
        resultMap.put("iscollection", iscollection);

        return ResponseResult.okResult(resultMap);
    }

    @Override
    public ResponseResult updateArticleNum(UpdateArticleDto dto) {
        ApArticle apArticle = (ApArticle) checkParam(dto).getData();
        switch (dto.getType()) {
            case LIKES:
                apArticle.setLikes(dto.getLike());
                break;
            case COLLECTION:
                apArticle.setCollection(dto.getCollect());
                break;
            case COMMENT:
                apArticle.setComment(dto.getComment());
                break;
            case VIEWS:
                apArticle.setViews(dto.getView());
                break;
        }
        return updateArticle(apArticle);
    }

    private ResponseResult checkParam(UpdateArticleDto dto) {
        if (dto == null && dto.getArticleId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "????????????");
        }
        ApArticle apArticle = getOne(Wrappers.<ApArticle>lambdaQuery().eq(ApArticle::getId, dto.getArticleId()));
        if (apArticle == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "???????????????");
        }
        return ResponseResult.okResult(apArticle);
    }

    private ResponseResult updateArticle(ApArticle apArticle) {
        boolean isUpdate = updateById(apArticle);
        if (BooleanUtils.isFalse(isUpdate)) {
            return ResponseResult.errorResult(501, "????????????");
        }
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /**
     * ??????????????????
     *
     * @param dto
     * @param type      1 ????????????   2 ????????????
     * @param firstPage true  ?????????  flase ?????????
     * @return
     */
    @Override
    public ResponseResult Hotload(ArticleHomeDto dto, Short type, boolean firstPage) {
        if (firstPage) {
            String jsonStr = cacheService.get(ArticleConstants.HOT_ARTICLE_FIRST_PAGE + dto.getTag());
            if (StringUtils.isNotBlank(jsonStr)) {
                List<HotArticleVo> hotArticleVoList = JSON.parseArray(jsonStr, HotArticleVo.class);
                ResponseResult responseResult = ResponseResult.okResult(hotArticleVoList);
                return responseResult;
            }
        }
        return load(type, dto);
    }

    /**
     * ?????????????????????  ??????????????????????????????????????????
     *
     * @param mess
     */
    @Override
    public void updateScore(ArticleVisitStreamMess mess) {
        //1.?????????????????????????????????????????????????????????
        ApArticle apArticle = updateArticle(mess);
        //2.?????????????????????
        Integer score = computeScore(apArticle);
        score = score * 3;

        //3.?????????????????????????????????????????????
        replaceDataToRedis(apArticle, score, ArticleConstants.HOT_ARTICLE_FIRST_PAGE + apArticle.getChannelId());

        //4.?????????????????????????????????
        replaceDataToRedis(apArticle, score, ArticleConstants.HOT_ARTICLE_FIRST_PAGE + ArticleConstants.DEFAULT_TAG);

    }

    /**
     * ???????????????????????????redis
     *
     * @param apArticle
     * @param score
     * @param s
     */
    private void replaceDataToRedis(ApArticle apArticle, Integer score, String s) {
        String articleListStr = cacheService.get(s);
        if (StringUtils.isNotBlank(articleListStr)) {
            List<HotArticleVo> hotArticleVoList = JSON.parseArray(articleListStr, HotArticleVo.class);

            boolean flag = true;

            //????????????????????????????????????????????????
            for (HotArticleVo hotArticleVo : hotArticleVoList) {
                if (hotArticleVo.getId().equals(apArticle.getId())) {
                    hotArticleVo.setScore(score);
                    flag = false;
                    break;
                }
            }

            //???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            if (flag) {
                if (hotArticleVoList.size() >= 30) {
                    hotArticleVoList = hotArticleVoList.stream().sorted(Comparator.comparing(HotArticleVo::getScore).reversed()).collect(Collectors.toList());
                    HotArticleVo lastHot = hotArticleVoList.get(hotArticleVoList.size() - 1);
                    if (lastHot.getScore() < score) {
                        hotArticleVoList.remove(lastHot);
                        HotArticleVo hot = new HotArticleVo();
                        BeanUtils.copyProperties(apArticle, hot);
                        hot.setScore(score);
                        hotArticleVoList.add(hot);
                    }


                } else {
                    HotArticleVo hot = new HotArticleVo();
                    BeanUtils.copyProperties(apArticle, hot);
                    hot.setScore(score);
                    hotArticleVoList.add(hot);
                }
            }
            //?????????redis
            hotArticleVoList = hotArticleVoList.stream().sorted(Comparator.comparing(HotArticleVo::getScore).reversed()).collect(Collectors.toList());
            cacheService.set(s, JSON.toJSONString(hotArticleVoList));

        }
    }

    /**
     * ??????db??????????????????
     *
     * @param mess
     */
    private ApArticle updateArticle(ArticleVisitStreamMess mess) {
        ApArticle apArticle = getById(mess.getArticleId());
        apArticle.setCollection(apArticle.getCollection() == null ? 0 : apArticle.getCollection() + mess.getCollect());
        apArticle.setComment(apArticle.getComment() == null ? 0 : apArticle.getComment() + mess.getComment());
        apArticle.setLikes(apArticle.getLikes() == null ? 0 : apArticle.getLikes() + mess.getLike());
        apArticle.setViews(apArticle.getViews() == null ? 0 : apArticle.getViews() + mess.getView());
        updateById(apArticle);
        return apArticle;

    }

    /**
     * ???????????????????????????
     *
     * @param apArticle
     * @return
     */
    private Integer computeScore(ApArticle apArticle) {
        Integer score = 0;
        if (apArticle.getLikes() != null) {
            score += apArticle.getLikes() * ArticleConstants.HOT_ARTICLE_LIKE_WEIGHT;
        }
        if (apArticle.getViews() != null) {
            score += apArticle.getViews();
        }
        if (apArticle.getComment() != null) {
            score += apArticle.getComment() * ArticleConstants.HOT_ARTICLE_COMMENT_WEIGHT;
        }
        if (apArticle.getCollection() != null) {
            score += apArticle.getCollection() * ArticleConstants.HOT_ARTICLE_COLLECTION_WEIGHT;
        }

        return score;
    }
}

