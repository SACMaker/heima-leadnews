package com.heima.wemedia.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.dtos.WmMaterialDto;
import com.heima.wemedia.service.WmMaterialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/v1/material")
public class WmMaterialController {
    @Autowired
    private WmMaterialService wmMaterialService;

    /**
     * 上传素材到minio
     *
     * @param multipartFile
     * @return
     */
    @PostMapping("/upload_picture")
    public ResponseResult login(MultipartFile multipartFile) {
        return wmMaterialService.uploadPicture(multipartFile);
    }

    /**
     * 素材列表查询
     *
     * @param dto
     * @return
     */
    @PostMapping("/list")
    public ResponseResult findList(@RequestBody WmMaterialDto dto) {
        return wmMaterialService.findList(dto);
    }

    /**
     * 删除素材
     *
     * @param id
     * @return
     */
    @GetMapping("/del_picture/{id}")
    public ResponseResult deleteMaterial(@PathVariable("id") Integer id) {
        return wmMaterialService.deleteMaterial(id);
    }
}
