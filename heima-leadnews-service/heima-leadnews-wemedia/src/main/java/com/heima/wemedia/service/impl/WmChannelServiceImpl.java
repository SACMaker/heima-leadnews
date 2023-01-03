package com.heima.wemedia.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.pojos.WmChannel;
import com.heima.wemedia.mapper.WmChannelMapper;
import com.heima.wemedia.service.WmChanneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional
public class WmChannelServiceImpl extends ServiceImpl<WmChannelMapper, WmChannel> implements WmChanneService {
    /**
     * 查询所有的频道
     *
     * @return
     */
    @Override
    public ResponseResult findAllChannels() {
<<<<<<< HEAD
        List<WmChannel> allChannels = list();
=======
        List<WmChannel> allChannels = this.list();
>>>>>>> 5edfeec (fun3-3-1:自媒体文章发布-自媒体文章管理-查询所有频道)
        return ResponseResult.okResult(allChannels);
    }
}