ALTER TABLE main_sms ADD COLUMN winner_sms TEXT NULL;
UPDATE main_sms SET winner_sms = 'لطفا با پشتیبانی استارتک تماس بگیرید. خبر خوش منتظر شماست.  09360590157' WHERE id = 1 AND (winner_sms IS NULL OR winner_sms = '');
