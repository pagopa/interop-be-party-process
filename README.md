# PDND Interoperability - Party Process Micro Service

---

## Deployment configuration

In order to properly deploy this component, some environment variables need to be configured.

### File storage engine

These variables configure the connection to the storage holding the component file artifacts. The storage can be either local or remote.


| Variable name | Variable type | Notes |
| ------------- | ------------- | ----- |
| **STORAGE_TYPE** | String | Admittable values are: `File`, `S3`, `BlobStorage` |
| **STORAGE_CONTAINER** | String | Defines the container holding the data (e.g.: S3 bucket name) |
| **STORAGE_ENDPOINT** | String | Defines the remote endpoint to connect to |
| **STORAGE_APPLICATION_ID** | String | Defines the user credential to access the remote endpoint |
| **STORAGE_APPLICATION_SECRET** | String | Defines the user password to access the remote endpoint |

:warning: - the usage of **STORAGE_TYPE** `File` should be only for development or testing purposes. For this specific type, all the other File storage property values are necessary, but basically useless. 

### Mail engine

These properties define the connection parameters to the SMTP server sending the e-mails.

| Variable name | Variable type | Notes |
| ------------- | ------------- | ----- |
| **MAIL_SENDER_ADDRESS** | String | Component mail sender address, e.g.: pagopa-interop@test.me |
| **SMTP_USR** | String | SMTP username |
| **SMTP_PSW** | String | SMTP user password |
| **SMTP_SERVER** | String | SMTP server address |
| **SMTP_PORT** | Integer | SMTP server port |

### Onboarding mail template configuration

These properties define the configuration for building a proper onboarding template mail according to deployment needs.  

| Variable name | Variable type | Notes |
| ------------- | ------------- | ----- |
| **MAIL_TEMPLATE_PATH** | String | Defines the link to the storage path containing the [mail template](#mail-template) |
| **MAIL_CONFIRM_PLACEHOLDER_NAME** | String | **Optional** variable. It defines the name of the placeholder holding the onboarding confirmation link. By default, the placeholder name is `confirmToken` |
| **MAIL_ONBOARDING_CONFIRMATION_LINK** | String | Defines the link to the onboarding confirmation (e.g.: `http://pagopa.it/onboarding-confirmation?token=`)|
| **MAIL_REJECT_PLACEHOLDER_NAME** | String | **Optional** variable. It defines the name of the placeholder holding the onboarding rejection link. By default, the placeholder name is `rejectToken` |
| **MAIL_ONBOARDING_REJECTION_LINK** | String | Defines the link to the onboarding rejection (e.g.: `http://pagopa.it/onboarding-reject?token=`)|


### Mail template

For onboarding mails, users MUST define a specific mail template, according to the following rules.
This template must be a JSON format with the following schema:

```json
{
   "$id": "https://pagopa.it/mail-template.schema.json",
   "$schema": "https://json-schema.org/draft/2020-12/schema",
   "type": "object", 
   "title": "MailTemplate",
   "required": [ "subject", "body" ],
   "properties": {
       "subject": {
           "type": "string",
           "description": "The subject of the e-mail."
       },
       "body": {
           "type": "string",
           "description": "The template mail"
       },
       "encoded": {
           "description": "Flag specifying if the template is Base64 encoded",
           "type": "boolean"
       }
   }
}
```

For example:

```json
   {
    "subject": "hello there", //mail subject, currently placeholders not supported here
    "body": "This is an onboarding confirmation link: ${confirmToken}", // template with placeholder 
    "encoded": false //optional
   }
```

where:  

- `subject` contains the subject of the email. It may be or not Base64 UTF-8 encoded
- `body` contains the template of the email body. It may be or not Base64 UTF-8 encoded
- `encoded` represents an optional flag that, if set to true, means that the template has both subject and body Base64 encoded.  

:warning: If you choose to encode the data, please mind that both `subject` and `body` MUST be encoded.  

---

So far, the mail template supports two different placeholders:

- a placeholder for the onboarding confirmation link, (`${confirmToken}`)
- a placeholder for the onboarding rejection link (`${rejectToken}`)

The placeholders MUST be defined according to the following syntax: `${PLACEHOLDER_NAME}`.  
E.g.: `this is the confirmation token: ${confirmToken}`.

Please, [see here](#onboarding-mail-template-configuration) for further details.

---

For each email, this service automatically interpolates the current onboarding token value to each of the placeholders. For example, assuming that:
- you've defined the following `confirmToken` placeholder: `http://pagopa.it/onboarding-confirmation?token=`
- you're doing an onboarding with token value `y4d4y4d4`

at runtime the email will contain the following link:

`http://pagopa.it/onboarding-confirmation?token=y4d4y4d4`






