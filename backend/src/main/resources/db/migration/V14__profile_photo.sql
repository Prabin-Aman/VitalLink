-- Profile photo (Settings › Change photo): the stored object's key, set once an upload is confirmed.
ALTER TABLE vitallink.profiles ADD COLUMN avatar_key text;
ALTER TABLE vitallink.profiles
  ADD CONSTRAINT profiles_avatar_key_shape CHECK (avatar_key ~ '^avatars/[0-9a-f-]{36}/[0-9a-f-]{36}$');
