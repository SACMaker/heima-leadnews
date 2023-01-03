package com.heima.wemedia.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.wemedia.service.WmChanneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/channel")
public class WmchannelController {
    @Autowired
    private WmChanneService wmChanneService;

    @GetMapping("/channels")
    public ResponseResult findAllChanne() {
        return wmChanneService.findAllChannels();
    }
}
