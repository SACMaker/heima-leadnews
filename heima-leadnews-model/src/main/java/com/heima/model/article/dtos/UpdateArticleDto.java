package com.heima.model.article.dtos;

import com.heima.model.mess.UpdateArticleType;
import lombok.Data;

@Data
public class UpdateArticleDto {

    /**
     * 修改文章的字段类型
     */
    private UpdateArticleType type;


    /**
     * 文章id
     */
    private Long articleId;
    /**
     * 阅读
     */
    private int view;
    /**
     * 收藏
     */
    private int collect;
    /**
     * 评论
     */
    private int comment;
    /**
     * 点赞
     */
    private int like;


    public void updateNum(UpdateArticleDto dto, int num) {
        switch (dto.getType()) {
            case LIKES:
                dto.setLike(dto.getLike() + num);
                break;
            case COLLECTION:
                dto.setCollect(dto.getCollect() + num);
                break;
            case COMMENT:
                dto.setComment(dto.getComment() + num);
                break;
            case VIEWS:
                dto.setView(dto.getView() + num);
                break;
        }
    }
}
