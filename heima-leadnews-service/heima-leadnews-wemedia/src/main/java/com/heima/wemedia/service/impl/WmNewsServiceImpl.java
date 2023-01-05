package com.heima.wemedia.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.model.common.dtos.PageResponseResult;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.wemedia.dtos.WmNewsPageReqDto;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.thread.WmThreadLocalUtil;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.service.WmNewsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional
public class WmNewsServiceImpl extends ServiceImpl<WmNewsMapper, WmNews> implements WmNewsService {

    /**
     * 条件查询文章list
     *
     * @param dto
     * @return
     */
    @Override
    public ResponseResult findAllWmNew(WmNewsPageReqDto dto) {
        //1.校验参数
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        dto.checkParam();
        //2.获取用户id
        WmUser user = WmThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        //3.分页器对象
        IPage<WmNews> page = new Page<>(dto.getPage(), dto.getSize());
        LambdaQueryWrapper<WmNews> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        //4.按条件查询
        //4.1按状态查询
        if (dto.getStatus() != null) {
            lambdaQueryWrapper.eq(WmNews::getStatus, dto.getStatus());
        }
        //4.2按频道查询
        if (dto.getChannelId() != null) {
            lambdaQueryWrapper.eq(WmNews::getChannelId, dto.getChannelId());
        }
        //4.3按日期范围查询
        if (dto.getBeginPubDate() != null && dto.getEndPubDate() != null) {
            //lambdaQueryWrapper.ge(WmNews::getPublishTime, dto.getBeginPubDate()).le(WmNews::getPublishTime, dto.getEndPubDate());
            lambdaQueryWrapper.between(WmNews::getPublishTime/*db字段*/, dto.getBeginPubDate(), dto.getEndPubDate());
        }
        //4.4按关键字查询
        if (dto.getKeyword() != null) {
            lambdaQueryWrapper.like(WmNews::getTitle, dto.getKeyword());
        }
        //4.5查询用户的news
        lambdaQueryWrapper.eq(WmNews::getUserId, user.getId());
        //4.6按照创建日期降序排序
        lambdaQueryWrapper.orderByDesc(WmNews::getCreatedTime);
        //5分页查询
        /*IPage<WmNews> page =*/
        this.page(page, lambdaQueryWrapper);
        //6.返回vo
        int total = (int) page.getTotal();
        log.debug("total:{}", total);
        PageResponseResult pageResponseResult = new PageResponseResult(dto.getPage(), dto.getSize(), total);
        List<WmNews> records = page.getRecords();
        pageResponseResult.setData(records);
        log.debug("pageResponseResult:{}", records);
        return pageResponseResult;
    }
}
