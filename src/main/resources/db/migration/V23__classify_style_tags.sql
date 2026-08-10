ALTER TABLE tag
    MODIFY COLUMN tag_type ENUM('COLOR', 'DETAIL', 'SILHOUETTE', 'STYLE', 'MATERIAL') NOT NULL;

UPDATE tag
SET tag_type = 'STYLE'
WHERE tag_name IN ('미니멀', '스트릿', '러블리', '캐주얼', '포멀')
  AND tag_type = 'DETAIL';
