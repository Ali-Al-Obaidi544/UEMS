-- Reference data needed by every installation (categories, tags, settings).
INSERT INTO app_setting VALUES ('session.timeout.minutes','20'), ('retention.years','3');
INSERT INTO category (name_en, name_ar) VALUES
 ('Academic','أكاديمي'),('Cultural','ثقافي'),('Sports','رياضي'),
 ('Technology','تقني'),('Social','اجتماعي'),('Career','مهني');
INSERT INTO tag (name_en, name_ar) VALUES
 ('Programming','البرمجة'),('Design','التصميم'),('Robotics','الروبوتات'),('AI','الذكاء الاصطناعي'),
 ('Football','كرة القدم'),('Basketball','كرة السلة'),('Music','الموسيقى'),('Art','الفن'),
 ('Entrepreneurship','ريادة الأعمال'),('Networking','التواصل المهني'),('Volunteering','التطوع'),('Research','البحث العلمي');
