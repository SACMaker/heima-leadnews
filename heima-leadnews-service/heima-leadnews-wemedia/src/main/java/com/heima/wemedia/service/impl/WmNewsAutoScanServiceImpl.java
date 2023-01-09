package com.heima.wemedia.service.impl;


import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.apis.article.IArticleClient;
import com.heima.common.aliyun.GreenImageScan;
import com.heima.common.aliyun.GreenTextScan;
import com.heima.common.tess4j.Tess4jClient;
import com.heima.file.service.FileStorageService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.wemedia.pojos.WmChannel;
import com.heima.model.wemedia.pojos.WmNews;
import com.heima.model.wemedia.pojos.WmSensitive;
import com.heima.model.wemedia.pojos.WmUser;
import com.heima.utils.common.SensitiveWordUtil;
import com.heima.wemedia.mapper.WmChannelMapper;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.mapper.WmSensitiveMapper;
import com.heima.wemedia.mapper.WmUserMapper;
import com.heima.wemedia.service.WmNewsAutoScanService;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.TesseractException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class WmNewsAutoScanServiceImpl implements WmNewsAutoScanService {
    @Autowired
    private WmNewsMapper wmNewsMapper;

    @Autowired
    private GreenImageScan greenImageScan;

    @Autowired
    private GreenTextScan greenTextScan;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private WmChannelMapper wmChannelMapper;
    @Autowired
    private WmUserMapper wmUserMapper;

    @Resource
    private IArticleClient articleClient;

    @Autowired
    private WmSensitiveMapper wmSensitiveMapper;

    @Autowired
    private Tess4jClient tess4jClient;

    /**
     * 自媒体文章审核
     *
     * @param id 自媒体文章id
     */
    @Override
    @Async  //标明当前方法是一个异步方法
    public void autoScanWmNews(Integer id) {
        //根据id查询新闻
        WmNews wmNews = wmNewsMapper.selectById(id);
        log.info("wmNews:{}", wmNews);
        if (wmNews == null) {
            throw new RuntimeException("WmNewsAutoScanServiceImpl-文章不存在");
        }

        if (wmNews.getStatus().equals(WmNews.Status.SUBMIT.getCode())) {
            //解析新闻里面文本和图片url
            Map<String, Object> textAndImages = extractTextImages(wmNews);
            //自管理的敏感词过滤
            boolean isSensitive = reviewSensitiveScan((String) textAndImages.get("content"), wmNews);
            if (!isSensitive) {
                return;
            }

            //对接阿里云默认审核成功
            //对接阿里云文本审核
            //boolean isTextScan = reviewTextScan((String) textAndImages.get("content"), wmNews);
            //if (!isTextScan) {
            //    return;
            //}
            //对接阿里云图片审核
            //3.审核图片  阿里云接口
            //boolean isImageScan = reviewleImageScan((List<String>) textAndImages.get("images"), wmNews);
            //if (!isImageScan) {
            //    return;
            //}

            //4.审核成功，保存app端的相关的文章数据-RPC
            ResponseResult responseResult = saveAppArticle(wmNews);
            if (!responseResult.getCode().equals(200)) {
                throw new RuntimeException("WmNewsAutoScanServiceImpl-文章审核，保存app端相关文章数据失败");
            }
            //回填用户app_db article_id
            wmNews.setArticleId((Long) responseResult.getData());
            updateWmNews(wmNews, WmNews.Status.PUBLISHED.getCode(), "审核成功");

        }

    }

    /**
     * 自管理的敏感词文本审核
     *
     * @param content
     * @param wmNews
     * @return
     */
    private boolean reviewSensitiveScan(String content, WmNews wmNews) {
        boolean flag = true;

        //获取所有的敏感词
        //SELECT sensitives FROM wm_sensitive;
        List<WmSensitive> wmSensitives = wmSensitiveMapper.selectList(Wrappers.<WmSensitive>lambdaQuery().select(WmSensitive::getSensitives));
        List<String> sensitiveList = wmSensitives.stream().map(WmSensitive::getSensitives).collect(Collectors.toList());

        //初始化敏感词库
        SensitiveWordUtil.initMap(sensitiveList);

        //查看文章中是否包含敏感词
        Map<String, Integer> map = SensitiveWordUtil.matchWords(content);
        if (map.size() > 0) {
            updateWmNews(wmNews, (short) 2, "当前文章中存在违规内容" + map);
            flag = false;
        }

        return flag;
    }

    /**
     * 保存app端相关的文章数据
     *
     * @param wmNews
     */
    private ResponseResult saveAppArticle(WmNews wmNews) {

        ArticleDto dto = new ArticleDto();
        //属性的拷贝
        BeanUtils.copyProperties(wmNews, dto);
        //文章的布局
        dto.setLayout(wmNews.getType());
        //频道
        WmChannel wmChannel = wmChannelMapper.selectById(wmNews.getChannelId());
        if (wmChannel != null) {
            dto.setChannelName(wmChannel.getName());
        }

        //作者
        dto.setAuthorId(wmNews.getUserId().longValue());
        WmUser wmUser = wmUserMapper.selectById(wmNews.getUserId());
        if (wmUser != null) {
            dto.setAuthorName(wmUser.getName());
        }

        //设置文章id
        if (wmNews.getArticleId() != null) {
            dto.setId(wmNews.getArticleId());
        }
        dto.setCreatedTime(new Date());
        //审核成功,open feign远程保存到app端文章数据
        ResponseResult responseResult = articleClient.saveArticle(dto);
        return responseResult;
    }


    /**
     * 审核图片
     *
     * @param images
     * @param wmNews
     * @return
     */
    private boolean reviewleImageScan(List<String> images, WmNews wmNews) throws TesseractException {
        boolean flag = true;

        if (images == null || images.size() == 0) {
            return flag;
        }

        //下载图片 minIO
        //图片去重
        images = images.stream().distinct().collect(Collectors.toList());

        List<byte[]> imageList = new ArrayList<>();

        try {
            for (String image : images) {
                byte[] bytes = fileStorageService.downLoadFile(image);
                //图片识别文字审核---begin-----

                //从byte[]转换为butteredImage
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                BufferedImage imageFile = ImageIO.read(in);
                //识别图片的文字
                String result = tess4jClient.doOCR(imageFile);

                //审核是否包含自管理的敏感词
                boolean isSensitive = reviewSensitiveScan(result, wmNews);
                if (!isSensitive) {
                    return isSensitive;
                }
                //图片识别文字审核---end-----
                imageList.add(bytes);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }


        //审核图片
        try {
            Map map = greenImageScan.imageScan(imageList);
            if (map != null) {
                //审核失败
                if (map.get("suggestion").equals("block")) {
                    flag = false;
                    updateWmNews(wmNews, WmNews.Status.FAIL.getCode(), "当前文章中存在违规内容");
                }

                //不确定信息  需要人工审核
                if (map.get("suggestion").equals("review")) {
                    flag = false;
                    updateWmNews(wmNews, WmNews.Status.ADMIN_AUTH.getCode(), "当前文章中存在不确定内容");
                }
            }

        } catch (Exception e) {
            flag = false;
            e.printStackTrace();
        }
        return flag;
    }


    /**
     * 审核文本
     *
     * @param content
     * @param wmNews
     */
    private boolean reviewTextScan(String content, WmNews wmNews) {
        boolean flag = true;

        if (content.length() == 0) {
            return flag;
        }

        try {
            Map map = greenTextScan.greeTextScan(content);
            if (map.get("suggestion").equals("block")) {
                flag = false;
                updateWmNews(wmNews, WmNews.Status.FAIL.getCode(), "当前文章中存在违规内容");
            }

            //不确定信息  需要人工审核
            if (map.get("suggestion").equals("review")) {
                flag = false;
                updateWmNews(wmNews, WmNews.Status.ADMIN_AUTH.getCode(), "当前文章中存在不确定内容,需要人工审核");
            }
        } catch (Exception ex) {
            flag = false;
            ex.printStackTrace();
        }
        return flag;
    }

    /**
     * 修改文章内容
     *
     * @param wmNews
     * @param status
     * @param reason
     */
    private void updateWmNews(WmNews wmNews, short status, String reason) {
        wmNews.setStatus(status);
        wmNews.setReason(reason);
        wmNewsMapper.updateById(wmNews);
    }

    /**
     * 从自媒体文章的内容中提取文本和图片(图片分为封面的图片和内容的图片)
     *
     * @param wmNews
     */
    private HashMap<String, Object> extractTextImages(WmNews wmNews) {
        //存储文字
        StringBuffer stringBuffer = new StringBuffer();
        //存储图片
        ArrayList<String> imagesUrlList = new ArrayList<>();
        String content = wmNews.getContent();
        if (StringUtils.isNotBlank(content)) {
            List<Map> contentMapList = JSONArray.parseArray(content, Map.class);
            for (Map map : contentMapList) {
                //提取内容文字
                if (map.get("type").equals("text")) {
                    stringBuffer.append(map.get("value"));
                }
                //提取内容图片url
                if (map.get("type").equals("image")) {
                    imagesUrlList.add((String) map.get("value"));
                }
            }
        }
        //提取标题文本
        String title = wmNews.getTitle();
        if (StringUtils.isNotBlank(title)) {
            stringBuffer.append(title);
        }
        //提取封面图片url
        String imagesUrl = wmNews.getImages();
        if (StringUtils.isNotBlank(imagesUrl)) {
            String[] split = imagesUrl.split(",");
            imagesUrlList.addAll(Arrays.asList(split));
        }

        HashMap<String, Object> resultMap = new HashMap<>();
        resultMap.put("content", stringBuffer.toString());
        resultMap.put("images", imagesUrlList);
        return resultMap;
    }
}




