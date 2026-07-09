UPDATE users
SET password_hash = '$2a$12$IsKDviFJnbYbHfuzoQ2P0OlvIgRAtzUjmkuwyv0Ze7fAfoqrUWevi'
WHERE char_length(password_hash) < 60;

UPDATE users
SET password_hash = '$2a$12$k6/Gj1/qw1/QfpPoW/Cc2ecYndl0U28pdSVRzrghe3hrsvjzp8/Xy'
WHERE email = 'seyd.njoya@icloud.com';
