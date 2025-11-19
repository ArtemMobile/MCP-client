https://github.com/chigwell/telegram-mcp

## Использование

### Базовое использование (только подключение к MCP серверу):
```bash
java -jar build/libs/MCP-client-1.0-SNAPSHOT.jar <path_to_server_script>
```

### Использование с YandexGPT (получение сообщений и создание саммари):
```bash
java -jar build/libs/MCP-client-1.0-SNAPSHOT.jar <path_to_server_script> <yandex_iam_token> <yandex_folder_id>
```

Пример:
```bash
java -jar build/libs/MCP-client-1.0-SNAPSHOT.jar C:\Users\mobile\telegram-mcp\main.py <your_iam_token> <your_folder_id>
```

## Функциональность

Приложение интегрировано с YandexGPT API и может:
1. Использовать function calling для вызова функции `get_messages` через YandexGPT
2. Получать сообщения из Telegram диалога (по умолчанию chat_id=408840411)
3. Создавать подробное резюме диалога на русском языке

## Требования

- IAM токен от Yandex Cloud
- Folder ID от Yandex Cloud
- Запущенный MCP сервер для Telegram (telegram-mcp)