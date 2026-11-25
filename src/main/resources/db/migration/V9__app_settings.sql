-- V9__app_settings.sql
-- Small key/value switches an admin can flip from the app. Currently only 'registration_open' ('true' / 'false'):
-- whether people may create their own account from the login page. No row means closed.
CREATE TABLE app_settings (
    name  VARCHAR(50)  PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);
