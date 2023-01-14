package com.heima.article.service.impl;

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
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleConfig;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        //1.参数校验
        //校验size
        Integer size = dto.getSize();
        if (size == null || size == 0) {
            size = 10;
        }
        size = Math.min(size, MAX_PAGE_SIZE);
        dto.setSize(size);
        //校验loadtype
        if (!loadtype.equals(ArticleConstants.LOADTYPE_LOAD_MORE) || loadtype.equals(ArticleConstants.LOADTYPE_LOAD_NEW)) {
            loadtype = ArticleConstants.LOADTYPE_LOAD_MORE;
        }
        //文章频道校验
        if (StringUtils.isEmpty(dto.getTag())) {
            dto.setTag(ArticleConstants.DEFAULT_TAG);
        }
        //时间校验
        if (dto.getMaxBehotTime() == null) {
            dto.setMaxBehotTime(new Date());
        }
        if (dto.getMinBehotTime() == null) {
            dto.setMinBehotTime(new Date());
        }
        //2查询文章
        List<ApArticle> apArticleList = apArticleMapper.loadArticleList(dto, loadtype);
        return ResponseResult.okResult(apArticleList);
    }

    /**
     * 保存app端相关文章
     *
     * @param dto
     * @return
     */
    @Override
    public ResponseResult saveArticle(ArticleDto dto) {
        //1.检查参数
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApArticle apArticle = new ApArticle();
        BeanUtils.copyProperties(dto, apArticle);

        //2.判断是否存在id
        if (dto.getId() == null) {
            //2.1 不存在id  保存  文章  文章配置  文章内容
            //保存文章
            //INSERT INTO ap_article ( id, title, layout, images, labels, publish_time ) VALUES ( ?, ?, ?, ?, ?, ? )
            save(apArticle);
            //保存配置
            ApArticleConfig apArticleConfig = new ApArticleConfig(apArticle.getId());
            //INSERT INTO ap_article_config ( id, article_id, is_comment, is_forward, is_down, is_delete ) VALUES ( ?, ?, ?, ?, ?, ? )
            apArticleConfigMapper.insert(apArticleConfig);
            //保存 文章内容
            ApArticleContent apArticleContent = new ApArticleContent();
            apArticleContent.setArticleId(apArticle.getId());
            apArticleContent.setContent(dto.getContent());
            //INSERT INTO ap_article_content ( id, article_id, content ) VALUES ( ?, ?, ? )
            apArticleContentMapper.insert(apArticleContent);
        } else {
            //2.2 存在id   修改  文章  文章内容
            //修改  文章
            updateById(apArticle);
            //修改文章内容
            //SELECT id,article_id,content FROM ap_article_content WHERE (article_id = ?)
            ApArticleContent apArticleContent =
                    apArticleContentMapper.selectOne(Wrappers.<ApArticleContent>lambdaQuery().eq(ApArticleContent::getArticleId, dto.getId()));
            apArticleContent.setContent(dto.getContent());
            //UPDATE ap_article_content SET article_id=?, content=? WHERE id=?
            apArticleContentMapper.updateById(apArticleContent);
        }
        //异步调用 生成静态文件上传到minio中
        articleFreemarkerService.buildArticleToMinIO(apArticle, dto.getContent());

        //3.结果返回  文章的id
        return ResponseResult.okResult(apArticle.getId());
    }

    @Override
    public ResponseResult loadArticleBehavior(ArticleInfoDto dto) {

        //0.检查参数
        if (dto == null || dto.getArticleId() == null || dto.getAuthorId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        //{ "isfollow": true, "islike": true,"isunlike": false,"iscollection": true }
        boolean isfollow = false, islike = false, isunlike = false, iscollection = false;

        ApUser user = AppThreadLocalUtil.getUser();
        if (user != null) {
            //喜欢行为
            String likeBehaviorJson = (String) cacheService.hGet("LIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString());
            if (StringUtils.isNotBlank(likeBehaviorJson)) {
                islike = true;
            }
            //不喜欢的行为
            String unLikeBehaviorJson = (String) cacheService.hGet("UNLIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString());
            if (StringUtils.isNotBlank(unLikeBehaviorJson)) {
                isunlike = true;
            }
            //是否收藏
            String collctionJson = (String) cacheService.hGet("COLLECTION-BEHAVIOR-" + dto.getArticleId(), user.getId().toString());
            if (StringUtils.isNotBlank(collctionJson)) {
                iscollection = true;
            }

            //是否关注
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

}

