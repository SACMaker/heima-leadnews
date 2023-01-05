package com.heima.wemedia.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.dtos.WmNewsDto;
import com.heima.model.wemedia.dtos.WmNewsPageReqDto;
import com.heima.model.wemedia.pojos.WmNews;


public interface WmNewsService extends IService<WmNews> {
    /**
     * 条件查询文章list
     *
     * @param dto
     * @return
     */
    public ResponseResult findAllWmNew(WmNewsPageReqDto dto);

    /**
     * 发布文章或保存草稿
     *
     * @param dto
     * @return
     */
    public ResponseResult submitNews(WmNewsDto dto);
}
