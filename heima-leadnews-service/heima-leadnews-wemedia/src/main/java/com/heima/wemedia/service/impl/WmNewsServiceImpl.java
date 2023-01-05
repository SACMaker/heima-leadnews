package com.heima.wemedia.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.common.constants.WemediaConstants;
import com.heima.common.exception.CustomException;
import com.heima.model.common.dtos.PageResponseResult;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.wemedia.dtos.WmNewsDto;
import com.heima.model.wemedia.dtos.WmNewsPageReqDto;
import com.heima.model.wemedia.pojos.WmMaterial;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmNewsMaterial;
import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.thread.WmThreadLocalUtil;
import com.heima.wemedia.mapper.WmMaterialMapper;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.mapper.WmNewsMaterialMapper;
import com.heima.wemedia.service.WmNewsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class WmNewsServiceImpl extends ServiceImpl<WmNewsMapper, WmNews> implements WmNewsService {
    @Autowired
    private WmMaterialMapper wmMaterialMapper;
    @Autowired
    private WmNewsMaterialMapper wmNewsMaterialMapper;


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

    /**
     * 发布文章或保存草稿
     * 需要保存文章和文章和素材的关系
     *
     * @param dto
     * @return
     */
    @Override
    public ResponseResult submitNews(WmNewsDto dto) {
        //0.参数检查
        if (dto == null || dto.getContent() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        //1.保存或者修改文章
        //vo->do
        WmNews wmNews = new WmNews();
        //属性copy
        BeanUtils.copyProperties(dto, wmNews);
        //封面图片list(应该是minIO url list)---> string
        if (dto.getImages() != null && dto.getImages().size() > 0) {
            //[1dddfsd.jpg,sdlfjldk.jpg]-->   1dddfsd.jpg,sdlfjldk.jpg
            String imageStr = StringUtils.join(dto.getImages(), ",");
            wmNews.setImages(imageStr);
        }
        //封面类型为自动-1,先设置初始值null,进行规则匹配
        if (dto.getType().equals(WemediaConstants.WM_NEWS_TYPE_AUTO)) {
            wmNews.setType(null);
        }
        //保存或者修改文章
        //id是否存在?
        //不存在就是save新的文章,保存后继续处理文章和素材的关系
        //存在就是修改,删除文章和素材关系,重新构建文章和素材的关系
        saveOrUpdateWmnews(wmNews);

        //2.判断是否为草稿 如果为草稿结束当前方法
        //保存草稿和提交审核的news的接口一致但是标志位(flag)不一致
        if (dto.getStatus().equals(WmNews.Status.NORMAL.getCode())) {
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }
        //3.不是草稿，保存文章内容图片与素材的关系
        //拿到文章内容的json string
        String content = dto.getContent();
        log.debug("content sub:{}" + content);
        //解析出文章内容里面所有的素材图片的url为url list
        List<String> materialContentImagesStringUrlList = ectractUrlInfo(content);
        //保存文章内容素材和新闻的关系
        saveRelativeInfoForContent(materialContentImagesStringUrlList, wmNews.getId());
        //4.不是草稿，保存文章封面图片与素材的关系，如果当前布局是自动，需要匹配封面图片
        saveRelativeInfoForCover(dto, wmNews, materialContentImagesStringUrlList);
        // TODO: 2023-01-05 审核文章
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /**
     * 第一个功能：如果当前封面类型为自动，则设置封面类型的数据
     * 匹配规则：
     * 1，如果内容图片大于等于1，小于3  单图  type 1
     * 2，如果内容图片大于等于3  多图  type 3
     * 3，如果内容没有图片，无图  type 0
     * <p>
     * 第二个功能：保存封面图片与素材的关系
     *
     * @param dto
     * @param wmNews
     * @param materialStringUrlList
     */
    private void saveRelativeInfoForCover(WmNewsDto dto, WmNews wmNews, List<String> materialStringUrlList) {
        List<String> coverImagesStringUrlList = dto.getImages();
        //封面类型为自动,封面图片匹配规则
        if (dto.getType().equals(WemediaConstants.WM_NEWS_TYPE_AUTO)) {
            if (materialStringUrlList.size() >= 3) {
                //内容图片>3设置封面图片为3-类型-多图
                wmNews.setType(WemediaConstants.WM_NEWS_MANY_IMAGE);
                coverImagesStringUrlList = materialStringUrlList.stream().limit(3).collect(Collectors.toList());
            } else if (materialStringUrlList.size() >= 1 && materialStringUrlList.size() < 3) {
                //内容图片>=1 <3设置封面图片为1-类型-单图
                wmNews.setType(WemediaConstants.WM_NEWS_SINGLE_IMAGE);
                coverImagesStringUrlList = materialStringUrlList.stream().limit(1).collect(Collectors.toList());
            } else {
                //无图
                wmNews.setType(WemediaConstants.WM_NEWS_NONE_IMAGE);
            }
        }
        //修改文章封面info
        if (coverImagesStringUrlList != null && coverImagesStringUrlList.size() > 0) {
            wmNews.setImages(StringUtils.join(coverImagesStringUrlList, ","));
            log.debug("wmNewsL:{}", wmNews);
        }
        //保存进db
        updateById(wmNews);

        if (coverImagesStringUrlList != null && coverImagesStringUrlList.size() > 0) {
            //保存文章封面图片与素材的关系
            saveRelativeInfo(coverImagesStringUrlList, wmNews.getId(), WemediaConstants.WM_COVER_REFERENCE/*封面引用-1*/);
        }
    }


    /**
     * 处理文章内容图片与素材的关系
     *
     * @param materialContentImagesStringUrlList
     * @param newsId
     */
    private void saveRelativeInfoForContent(List<String> materialContentImagesStringUrlList, Integer newsId) {
        saveRelativeInfo(materialContentImagesStringUrlList, newsId, WemediaConstants.WM_CONTENT_REFERENCE/*引用关系0-内容引用*/);
    }

    /**
     * 保存文章内容素材和新闻的关系
     *
     * @param materialContentImagesStringUrlList
     * @param newsId
     * @param type
     */
    private void saveRelativeInfo(List<String> materialContentImagesStringUrlList, Integer newsId, Short type) {
        if (materialContentImagesStringUrlList != null && materialContentImagesStringUrlList.size() > 0) {
            //通过图片的url查询素材的id
            List<WmMaterial> dbMaterials = wmMaterialMapper.selectList(Wrappers.<WmMaterial>lambdaQuery().in(WmMaterial::getUrl, materialContentImagesStringUrlList));
            if (dbMaterials == null && dbMaterials.size() == 0) {
                throw new CustomException(AppHttpCodeEnum.MATERIASL_REFERENCE_FAIL);
            }
            if (materialContentImagesStringUrlList.size() != dbMaterials.size()) {
                throw new CustomException(AppHttpCodeEnum.MATERIASL_REFERENCE_FAIL);
            }
            List<Integer> idList = dbMaterials.stream().map(WmMaterial::getId).collect(Collectors.toList());
            wmNewsMaterialMapper.saveRelations(idList, newsId, type);
        }

    }


    /**
     * 提取文章内容的图片信息
     *
     * @param content
     */
    private List<String> ectractUrlInfo(String content) {
        ArrayList<String> materialContentImagesStringUrlList = new ArrayList<>();
        List<Map> mapList = JSON.parseArray(content, Map.class);
        log.debug("mapList:{}", mapList);
        for (Map map : mapList) {
            log.debug("map:{}", map);
            if (map.get("type").equals("image")) {
                String imgUrl = (String) map.get("value");
                materialContentImagesStringUrlList.add(imgUrl);
            }
        }
        return materialContentImagesStringUrlList;
    }

    /**
     * 保存或者修改文章
     *
     * @param wmNews
     */
    private void saveOrUpdateWmnews(WmNews wmNews) {
        //补全属性
        wmNews.setUserId(WmThreadLocalUtil.getUser().getId());
        wmNews.setCreatedTime(new Date());
        wmNews.setSubmitedTime(new Date());
        wmNews.setEnable((short) 1);//默认上架
        if (wmNews.getId() == null) {
            //保存news
            this.save(wmNews);
        } else {
            //修改,修改的是草稿里面的文章,之前已经保存了new和素材的关系
            LambdaQueryWrapper<WmNewsMaterial> lambdaQueryWrapper = Wrappers.<WmNewsMaterial>lambdaQuery().eq(WmNewsMaterial::getNewsId, wmNews.getId());
            //删除news和素材的关系
            wmNewsMaterialMapper.delete(lambdaQueryWrapper);
            //更新news
            updateById(wmNews);
        }
    }
}
