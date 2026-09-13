-- 联调图片 URL（已由 scripts/seed_oss_images.py 上传至 OSS）
-- 重新灌库前请先运行: python scripts/seed_oss_images.py && python scripts/apply_seed_urls_to_db.py

-- 用户头像（tb_user.avatar_url）
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-1.jpg', update_time = NOW() WHERE id = 1;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-3.jpg', update_time = NOW() WHERE id = 3;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-4.jpg', update_time = NOW() WHERE id = 4;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-5.jpg', update_time = NOW() WHERE id = 5;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-6.jpg', update_time = NOW() WHERE id = 6;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-7.jpg', update_time = NOW() WHERE id = 7;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-9.jpg', update_time = NOW() WHERE id = 9;
UPDATE tb_user SET avatar_url = 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/avatars/user-10.jpg', update_time = NOW() WHERE id = 10;

-- 帖子封面（tb_content_image）
DELETE FROM tb_content_image WHERE image_url LIKE '%dev-seed/posts%';
INSERT INTO tb_content_image (content_id, image_url, sort) VALUES
(3,  'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-3.jpg', 0),
(4,  'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-4.jpg', 0),
(6,  'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-6.jpg', 0),
(10, 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-10.jpg', 0),
(11, 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-11.jpg', 0),
(12, 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-12.jpg', 0),
(13, 'https://java-ai13580089828.oss-cn-beijing.aliyuncs.com/dev-seed/posts/content-13.jpg', 0);
