package com.heima.behavior.service.impl;

import com.alibaba.fastjson.JSON;
import com.heima.apis.article.IArticleClient;
import com.heima.behavior.service.ApLikesBehaviorService;
import com.heima.common.constants.HotArticleConstants;
import com.heima.common.redis.CacheService;
import com.heima.model.article.dtos.UpdateArticleDto;
import com.heima.model.behavior.dtos.LikesBehaviorDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.mess.UpdateArticleMess;
import com.heima.model.mess.UpdateArticleType;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional
@Slf4j
public class ApLikesBehaviorServiceImpl implements ApLikesBehaviorService {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private IArticleClient articleClient;

    @Autowired
    private KafkaTemplate kafkaTemplate;

    @Override
    public ResponseResult like(LikesBehaviorDto dto) {

        //1.检查参数
        if (dto == null || dto.getArticleId() == null || checkParam(dto)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        //2.是否登录
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        UpdateArticleDto updateArticleDto = new UpdateArticleDto();
        updateArticleDto.setArticleId(dto.getArticleId());
        updateArticleDto.setType(UpdateArticleType.LIKES);

        //KafkaStream封装类封装数据
        UpdateArticleMess mess = new UpdateArticleMess();
        mess.setArticleId(dto.getArticleId());
        mess.setType(UpdateArticleMess.UpdateArticleType.LIKES);
        //3.点赞  保存数据
        //0-点赞
        if (dto.getOperation() == 0) {

            Object obj = cacheService.hGet("LIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString());
            //检查之前是否已经点赞
            if (obj != null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "已点赞");
            }
            // 保存当前key
            log.info("保存当前key:{} ,{}, {}", dto.getArticleId(), user.getId(), dto);
            cacheService.hPut("LIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString(), JSON.toJSONString(dto));
            updateArticleDto.updateNum(updateArticleDto, 1);

            mess.setAdd(1);
        } else {
            //1-取消点赞
            // 删除当前key
            log.info("删除当前key:{}, {}", dto.getArticleId(), user.getId());
            cacheService.hDelete("LIKE-BEHAVIOR-" + dto.getArticleId().toString(), user.getId().toString());
            updateArticleDto.updateNum(updateArticleDto, -1);

            mess.setAdd(-1);
        }
        articleClient.updateArticleNum(updateArticleDto);

        //发送消息，数据聚合
        kafkaTemplate.send(HotArticleConstants.HOT_ARTICLE_SCORE_TOPIC, JSON.toJSONString(mess));
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /**
     * 检查参数
     * @return
     */
    private boolean checkParam(LikesBehaviorDto dto){
        //点赞类型 0文章 1动态 2评论                     操作类型0点赞1取消点赞
        if(dto.getType() > 2 || dto.getType() < 0 || dto.getOperation() > 1 || dto.getOperation() < 0){
            return true;
        }
        return false;
    }
}
