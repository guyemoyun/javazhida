const state = {
  providers: [],
  conversations: [],
  activeId: null,
  sending: false,
  abortController: null,
  editingMessageId: null,
  searchQuery: "",
  persistenceTimer: null,
  persistencePromise: Promise.resolve(),
  streamRenderFrame: null
};

const SNAPSHOT_VERSION = 1;
const LEGACY_STORAGE_KEY = "zhida-conversations";
const MAX_HISTORY_MESSAGES = 100;

const providerLabels = {
  deepseek: "DeepSeek（深度求索）",
  glm: "GLM（智谱清言）",
  openai: "OpenAI（开放人工智能）",
  qwen: "Qwen（通义千问）"
};

const elements = {
  provider: document.querySelector("#provider"),
  model: document.querySelector("#model"),
  messages: document.querySelector("#messages"),
  question: document.querySelector("#question"),
  form: document.querySelector("#chat-form"),
  send: document.querySelector("#send"),
  stop: document.querySelector("#stop"),
  status: document.querySelector("#composer-status"),
  notice: document.querySelector("#notice"),
  title: document.querySelector("#conversation-title"),
  list: document.querySelector("#conversation-list"),
  count: document.querySelector("#conversation-count"),
  sidebar: document.querySelector("#sidebar"),
  backdrop: document.querySelector("#sidebar-backdrop"),
  search: document.querySelector("#message-search"),
  searchResult: document.querySelector("#search-result"),
  stats: document.querySelector("#message-stats"),
  clearMessages: document.querySelector("#clear-messages"),
  importData: document.querySelector("#import-data"),
  exportData: document.querySelector("#export-data"),
  importFile: document.querySelector("#import-file")
};

function nowIso() {
  return new Date().toISOString();
}

function normalizeConversations(input) {
  if (!Array.isArray(input)) return [];
  const now = nowIso();
  return input.filter((conversation) => conversation && typeof conversation === "object").map((conversation) => {
    const createdAt = conversation.createdAt || now;
    const messages = Array.isArray(conversation.messages)
      ? conversation.messages
        .filter((message) => message && (message.role === "user" || message.role === "assistant")
          && typeof message.content === "string" && message.content.trim())
        .map((message) => ({
          id: message.id || crypto.randomUUID(),
          role: message.role,
          content: message.content,
          provider: message.provider || "",
          model: message.model || "",
          createdAt: message.createdAt || createdAt,
          status: normalizeMessageStatus(message.status),
          usage: message.usage || null
        }))
      : [];
    return {
      id: conversation.id || crypto.randomUUID(),
      title: String(conversation.title || "新建对话").slice(0, 200),
      messages,
      provider: conversation.provider || "",
      model: conversation.model || "",
      createdAt,
      updatedAt: conversation.updatedAt || createdAt
    };
  });
}

function normalizeMessageStatus(status) {
  if (status === "streaming") return "stopped";
  return ["complete", "stopped", "error", "failed"].includes(status) ? status : "complete";
}

function snapshot() {
  return {
    version: SNAPSHOT_VERSION,
    conversations: state.conversations.map((conversation) => ({
      ...conversation,
      messages: conversation.messages
        .filter((message) => message.content.trim())
        .map(({ usage, ...message }) => message)
    }))
  };
}

function save() {
  window.clearTimeout(state.persistenceTimer);
  state.persistenceTimer = window.setTimeout(() => {
    persistNow().catch(reportPersistenceError);
  }, 250);
}

async function persistNow() {
  window.clearTimeout(state.persistenceTimer);
  state.persistenceTimer = null;
  const body = JSON.stringify(snapshot());
  const operation = state.persistencePromise.catch(() => {}).then(async () => {
    const response = await fetch("/api/conversations", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok || payload?.code !== 200) {
      throw new Error(payload?.msg || "本地会话保存失败");
    }
  });
  state.persistencePromise = operation;
  return operation;
}

function reportPersistenceError(error) {
  elements.notice.textContent = `${error.message}。请导出数据备份后重试。`;
}

function readLegacyConversations() {
  const raw = localStorage.getItem(LEGACY_STORAGE_KEY);
  if (!raw) return { conversations: [], error: null };
  try {
    return { conversations: normalizeConversations(JSON.parse(raw)), error: null };
  } catch (error) {
    return { conversations: [], error: "旧版浏览器记录已损坏，未自动覆盖原数据。" };
  }
}

async function loadConversations() {
  const response = await fetch("/api/conversations");
  const payload = await response.json();
  if (!response.ok || payload.code !== 200) throw new Error(payload.msg || "无法读取本地会话");
  const serverConversations = normalizeConversations(payload.data?.conversations);
  const legacy = readLegacyConversations();
  if (!serverConversations.length && legacy.conversations.length) {
    state.conversations = legacy.conversations;
    await persistNow();
    localStorage.removeItem(LEGACY_STORAGE_KEY);
    elements.notice.textContent = "旧版浏览器对话已迁移到本机 SQLite。";
  } else {
    state.conversations = serverConversations;
    if (serverConversations.length) localStorage.removeItem(LEGACY_STORAGE_KEY);
    if (legacy.error) elements.notice.textContent = legacy.error;
  }
}

function activeConversation() {
  return state.conversations.find((conversation) => conversation.id === state.activeId);
}

function touchConversation(conversation) {
  conversation.updatedAt = nowIso();
}

function newConversation() {
  if (state.sending) return;
  const defaultProvider = state.providers[0];
  const timestamp = nowIso();
  const conversation = {
    id: crypto.randomUUID(),
    title: "新建对话",
    messages: [],
    provider: defaultProvider?.id || "",
    model: defaultProvider?.model || "",
    createdAt: timestamp,
    updatedAt: timestamp
  };
  state.conversations.unshift(conversation);
  state.activeId = conversation.id;
  resetMessageTools();
  save();
  render();
  elements.question.focus();
}

function render() {
  const conversation = activeConversation();
  if (!conversation) return;
  elements.title.textContent = conversation.title;
  renderConversationList();
  renderMessages(conversation);
  renderMessageStats(conversation);
  syncControls(conversation);
}

function renderConversationList() {
  elements.list.replaceChildren();
  elements.count.textContent = state.conversations.length;
  state.conversations.forEach((conversation) => {
    const row = document.createElement("div");
    row.className = `conversation-row ${conversation.id === state.activeId ? "active" : ""}`;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "conversation";
    button.textContent = conversation.title;
    button.disabled = state.sending;
    button.addEventListener("click", () => {
      if (state.sending) return;
      state.activeId = conversation.id;
      resetMessageTools();
      closeSidebar();
      render();
    });
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "delete-conversation";
    remove.textContent = "×";
    remove.title = "删除对话";
    remove.disabled = state.sending;
    remove.setAttribute("aria-label", `删除对话：${conversation.title}`);
    remove.addEventListener("click", () => deleteConversation(conversation.id));
    row.append(button, remove);
    elements.list.append(row);
  });
}

function deleteConversation(id) {
  if (state.sending) return;
  const target = state.conversations.find((conversation) => conversation.id === id);
  if (!target || !window.confirm(`确定删除对话“${target.title}”吗？`)) return;
  state.conversations = state.conversations.filter((conversation) => conversation.id !== id);
  if (!state.conversations.length) {
    newConversation();
    return;
  }
  if (state.activeId === id) {
    state.activeId = state.conversations[0].id;
    resetMessageTools();
  }
  save();
  render();
}

function openSidebar() {
  elements.sidebar.classList.add("open");
  elements.backdrop.classList.add("open");
}

function closeSidebar() {
  elements.sidebar.classList.remove("open");
  elements.backdrop.classList.remove("open");
}

function renderMessages(conversation) {
  elements.messages.replaceChildren();
  if (!conversation.messages.length) {
    const welcome = document.createElement("div");
    welcome.className = "empty-state";
    welcome.innerHTML = "<h2>开始一个问题</h2><p>选择已配置的服务商与模型，然后输入问题。对话保存在本机，云模型仍会接收发送给它的内容。</p>";
    elements.messages.append(welcome);
    return;
  }
  const query = state.searchQuery.toLocaleLowerCase();
  const visibleMessages = conversation.messages.filter((message) => !query || message.content.toLocaleLowerCase().includes(query));
  if (!visibleMessages.length) {
    const empty = document.createElement("div");
    empty.className = "empty-search";
    empty.textContent = `没有找到包含“${state.searchQuery}”的消息`;
    elements.messages.append(empty);
    return;
  }
  visibleMessages.forEach((message) => addMessageElement(conversation, message));
  if (!query) elements.messages.scrollTop = elements.messages.scrollHeight;
}

function addMessageElement(conversation, message) {
  const row = document.createElement("article");
  row.className = `message ${message.role} status-${message.status}`;
  row.dataset.messageId = message.id;
  const avatar = document.createElement("div");
  avatar.className = "avatar";
  avatar.textContent = message.role === "user" ? "你" : "AI";
  const content = document.createElement("div");
  content.className = "message-body";
  const meta = document.createElement("div");
  meta.className = "message-meta";
  const metaLabel = document.createElement("span");
  metaLabel.textContent = messageMeta(message);
  const actions = document.createElement("span");
  actions.className = "message-actions";
  actions.append(createMessageAction("复制", "copy-message", () => copyText(message.content)));
  if (message.role === "assistant") {
    actions.append(createMessageAction(
      message.status === "complete" ? "重新生成" : "重试",
      "retry-message",
      () => retryAssistantMessage(conversation, message.id)
    ));
  }
  actions.append(
    createMessageAction("编辑", "edit-message", () => beginEditMessage(message.id)),
    createMessageAction("删除", "delete-message", () => deleteMessage(message.id))
  );
  meta.append(metaLabel, actions);

  if (state.editingMessageId === message.id) {
    content.append(meta, createMessageEditor(message));
  } else {
    const text = document.createElement("div");
    text.className = "message-content";
    renderMessageContent(text, message, state.searchQuery);
    content.append(meta, text);
  }
  row.append(avatar, content);
  elements.messages.append(row);
}

function messageMeta(message) {
  const source = message.role === "user" ? "你的问题" : `${message.provider || "模型"} · ${message.model || ""}`;
  const labels = { streaming: "生成中", stopped: "已停止", error: "失败", failed: "失败" };
  return labels[message.status] ? `${source} · ${labels[message.status]}` : source;
}

function renderMessageContent(container, message, query) {
  if (query) {
    appendHighlightedText(container, message.content, query);
    return;
  }
  if (message.role !== "assistant" || !window.marked || !window.DOMPurify) {
    container.textContent = message.content;
    return;
  }
  const html = window.marked.parse(message.content, { breaks: true, gfm: true });
  container.innerHTML = window.DOMPurify.sanitize(html);
  container.querySelectorAll("a").forEach((link) => {
    link.target = "_blank";
    link.rel = "noopener noreferrer";
  });
  container.querySelectorAll("pre code").forEach((code) => {
    if (window.hljs) window.hljs.highlightElement(code);
    const pre = code.parentElement;
    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "code-copy";
    copy.textContent = "复制";
    copy.title = "复制代码";
    copy.addEventListener("click", () => copyText(code.textContent, copy));
    pre.append(copy);
  });
}

async function copyText(content, button) {
  try {
    await navigator.clipboard.writeText(content);
    if (button) {
      button.textContent = "已复制";
      window.setTimeout(() => { button.textContent = "复制"; }, 1200);
    } else {
      elements.notice.textContent = "消息已复制。";
      window.setTimeout(() => {
        if (elements.notice.textContent === "消息已复制。") elements.notice.textContent = "";
      }, 1200);
    }
  } catch (error) {
    elements.notice.textContent = "无法访问剪贴板。";
  }
}

function createMessageAction(label, className, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className;
  button.textContent = label;
  button.title = label;
  button.setAttribute("aria-label", `${label}这条消息`);
  button.disabled = state.sending || state.editingMessageId !== null;
  button.addEventListener("click", onClick);
  return button;
}

function createMessageEditor(message) {
  const editor = document.createElement("div");
  editor.className = "message-editor";
  const input = document.createElement("textarea");
  input.value = message.content;
  input.rows = Math.min(10, Math.max(3, message.content.split("\n").length));
  input.maxLength = message.role === "user" ? 12000 : 200000;
  input.setAttribute("aria-label", "编辑消息内容");
  const footer = document.createElement("div");
  footer.className = "editor-actions";
  const cancel = document.createElement("button");
  cancel.type = "button";
  cancel.className = "secondary-action";
  cancel.textContent = "取消";
  cancel.addEventListener("click", cancelEditMessage);
  const saveButton = document.createElement("button");
  saveButton.type = "button";
  saveButton.className = "primary-action";
  saveButton.textContent = "保存";
  saveButton.addEventListener("click", () => saveEditedMessage(message.id, input.value));
  input.addEventListener("keydown", (event) => {
    if (event.key === "Escape") cancelEditMessage();
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) saveEditedMessage(message.id, input.value);
  });
  footer.append(cancel, saveButton);
  editor.append(input, footer);
  requestAnimationFrame(() => {
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
  });
  return editor;
}

function appendHighlightedText(container, content, query) {
  if (!query) {
    container.textContent = content;
    return;
  }
  const normalizedContent = content.toLocaleLowerCase();
  const normalizedQuery = query.toLocaleLowerCase();
  let cursor = 0;
  let matchIndex = normalizedContent.indexOf(normalizedQuery);
  while (matchIndex !== -1) {
    container.append(document.createTextNode(content.slice(cursor, matchIndex)));
    const mark = document.createElement("mark");
    mark.textContent = content.slice(matchIndex, matchIndex + query.length);
    container.append(mark);
    cursor = matchIndex + query.length;
    matchIndex = normalizedContent.indexOf(normalizedQuery, cursor);
  }
  container.append(document.createTextNode(content.slice(cursor)));
}

function beginEditMessage(id) {
  if (state.sending) return;
  state.editingMessageId = id;
  render();
}

function cancelEditMessage() {
  state.editingMessageId = null;
  render();
}

function saveEditedMessage(id, value) {
  const content = value.trim();
  if (!content) {
    elements.notice.textContent = "消息内容不能为空。";
    return;
  }
  const conversation = activeConversation();
  const message = conversation.messages.find((item) => item.id === id);
  if (!message) return;
  message.content = content;
  message.status = "complete";
  state.editingMessageId = null;
  elements.notice.textContent = "";
  syncConversationTitle(conversation);
  touchConversation(conversation);
  save();
  render();
}

function deleteMessage(id) {
  if (state.sending || state.editingMessageId !== null) return;
  const conversation = activeConversation();
  conversation.messages = conversation.messages.filter((message) => message.id !== id);
  syncConversationTitle(conversation);
  touchConversation(conversation);
  save();
  render();
}

function clearMessages() {
  const conversation = activeConversation();
  if (!conversation.messages.length || state.sending) return;
  if (!window.confirm("确定要删除当前对话中的所有消息吗？此操作无法撤销。")) return;
  conversation.messages = [];
  conversation.title = "新建对话";
  touchConversation(conversation);
  resetMessageTools();
  save();
  render();
  elements.question.focus();
}

function syncConversationTitle(conversation) {
  const firstUserMessage = conversation.messages.find((message) => message.role === "user");
  conversation.title = firstUserMessage ? firstUserMessage.content.slice(0, 28) : "新建对话";
}

function resetMessageTools() {
  state.editingMessageId = null;
  state.searchQuery = "";
  elements.search.value = "";
}

function renderMessageStats(conversation) {
  const combinedContent = conversation.messages.map((message) => message.content).join("");
  const bytes = new TextEncoder().encode(combinedContent).length;
  const cjkCount = (combinedContent.match(/[\u3400-\u9fff\uf900-\ufaff]/gu) || []).length;
  const remainingLength = Array.from(combinedContent.replace(/[\u3400-\u9fff\uf900-\ufaff]/gu, "")).length;
  const estimatedTokens = cjkCount + Math.ceil(remainingLength / 4);
  elements.stats.textContent = `${conversation.messages.length} 条消息 · ${bytes.toLocaleString()} 字节 · 约 ${estimatedTokens.toLocaleString()} tokens`;
  const matches = state.searchQuery
    ? conversation.messages.filter((message) => message.content.toLocaleLowerCase().includes(state.searchQuery.toLocaleLowerCase())).length
    : 0;
  elements.searchResult.textContent = state.searchQuery ? `${matches} 条结果` : "";
  elements.clearMessages.disabled = !conversation.messages.length || state.sending;
}

function syncControls(conversation) {
  const provider = state.providers.find((item) => item.id === conversation.provider) || state.providers[0];
  if (provider) {
    elements.provider.value = provider.id;
    elements.model.value = conversation.model || provider.model;
  }
  const configured = provider?.configured;
  elements.provider.disabled = state.sending;
  elements.model.disabled = state.sending;
  elements.send.disabled = state.sending || !configured;
  elements.stop.hidden = !state.sending;
  elements.send.hidden = state.sending;
  elements.importData.disabled = state.sending;
  elements.exportData.disabled = state.sending;
  if (!state.sending && !configured) {
    elements.notice.textContent = "请先在 config/model-config.properties 中填写该服务商的 API_KEY，然后重新启动项目。";
  } else if (configured && elements.notice.textContent.startsWith("请先在 config/model-config.properties")) {
    elements.notice.textContent = "";
  }
}

async function loadProviders() {
  const response = await fetch("/api/providers");
  const payload = await response.json();
  if (!response.ok || payload.code !== 200) throw new Error(payload.msg || "无法读取模型配置");
  state.providers = payload.data;
  elements.provider.replaceChildren();
  state.providers.forEach((provider) => {
    const option = document.createElement("option");
    option.value = provider.id;
    const displayName = providerLabels[provider.id] || provider.name;
    option.textContent = `${displayName}${provider.configured ? "" : "（未配置）"}`;
    elements.provider.append(option);
  });
}

function selectedProvider() {
  return state.providers.find((provider) => provider.id === elements.provider.value);
}

async function startNewTurn(content) {
  const conversation = activeConversation();
  const provider = selectedProvider();
  if (!conversation || !provider?.configured) return;
  const timestamp = nowIso();
  const userMessage = {
    id: crypto.randomUUID(),
    role: "user",
    content,
    provider: "",
    model: "",
    createdAt: timestamp,
    status: "complete"
  };
  conversation.messages.push(userMessage);
  if (conversation.title === "新建对话") conversation.title = content.slice(0, 28);
  conversation.provider = provider.id;
  conversation.model = elements.model.value.trim();
  touchConversation(conversation);
  elements.question.value = "";
  await runTurn(conversation, userMessage);
}

async function retryAssistantMessage(conversation, assistantId) {
  if (state.sending) return;
  const assistantIndex = conversation.messages.findIndex((message) => message.id === assistantId);
  if (assistantIndex < 0) return;
  let userIndex = assistantIndex - 1;
  while (userIndex >= 0 && conversation.messages[userIndex].role !== "user") userIndex--;
  if (userIndex < 0) return;
  if (assistantIndex < conversation.messages.length - 1
      && !window.confirm("重新生成会删除这条回答之后的消息，是否继续？")) return;
  const userMessage = conversation.messages[userIndex];
  conversation.messages.splice(assistantIndex);
  touchConversation(conversation);
  await runTurn(conversation, userMessage);
}

async function runTurn(conversation, userMessage) {
  const provider = state.providers.find((item) => item.id === conversation.provider);
  if (!provider?.configured || state.sending) return;
  const userIndex = conversation.messages.findIndex((message) => message.id === userMessage.id);
  const history = conversation.messages
    .slice(Math.max(0, userIndex - MAX_HISTORY_MESSAGES), userIndex)
    .filter((message) => ["complete", "stopped"].includes(message.status)
      && (message.role === "user" || message.role === "assistant"))
    .map(({ role, content }) => ({ role, content }));
  const assistantMessage = {
    id: crypto.randomUUID(),
    role: "assistant",
    content: "",
    provider: provider.id,
    model: conversation.model,
    createdAt: nowIso(),
    status: "streaming",
    usage: null
  };

  state.sending = true;
  userMessage.status = "complete";
  state.abortController = new AbortController();
  elements.notice.textContent = "";
  elements.status.textContent = "模型正在回答...";
  conversation.messages.splice(userIndex + 1, 0, assistantMessage);
  render();

  try {
    await persistNow();
    await streamChat({
      provider: provider.id,
      model: conversation.model,
      message: userMessage.content,
      history
    }, state.abortController.signal, (eventName, data) => {
      if (eventName === "meta") {
        assistantMessage.provider = data.provider || assistantMessage.provider;
        assistantMessage.model = data.model || assistantMessage.model;
      } else if (eventName === "delta") {
        assistantMessage.content += data.content || "";
        scheduleStreamingRender(conversation);
      } else if (eventName === "usage") {
        try { assistantMessage.usage = JSON.parse(data.content); } catch (ignored) { assistantMessage.usage = null; }
      } else if (eventName === "error") {
        throw new Error(data.message || "模型流式请求失败");
      }
    });
    if (!assistantMessage.content.trim()) throw new Error("模型未返回有效回答");
    assistantMessage.status = "complete";
  } catch (error) {
    if (error.name === "AbortError") {
      assistantMessage.status = "stopped";
      if (!assistantMessage.content.trim()) assistantMessage.content = "已停止生成。";
    } else {
      assistantMessage.status = "error";
      userMessage.status = "failed";
      if (!assistantMessage.content.trim()) assistantMessage.content = `请求失败：${error.message}`;
      elements.notice.textContent = error.message;
    }
  } finally {
    state.sending = false;
    state.abortController = null;
    touchConversation(conversation);
    elements.status.textContent = "Enter 发送，Shift + Enter 换行";
    await persistNow().catch(reportPersistenceError);
    render();
    elements.question.focus();
  }
}

async function streamChat(request, signal, onEvent) {
  const response = await fetch("/api/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    signal
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new Error(payload?.msg || "模型请求失败");
  }
  if (!response.body) throw new Error("浏览器不支持流式响应");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || "";
    for (const block of blocks) dispatchServerEvent(block, onEvent);
    if (done) break;
  }
  if (buffer.trim()) dispatchServerEvent(buffer, onEvent);
}

function dispatchServerEvent(block, onEvent) {
  let eventName = "message";
  const dataLines = [];
  block.split(/\r?\n/).forEach((line) => {
    if (line.startsWith("event:")) eventName = line.slice(6).trim();
    if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
  });
  if (!dataLines.length) return;
  const raw = dataLines.join("\n");
  let data;
  try { data = JSON.parse(raw); } catch (error) { data = { content: raw }; }
  onEvent(eventName, data);
}

function scheduleStreamingRender(conversation) {
  save();
  if (state.streamRenderFrame !== null) return;
  state.streamRenderFrame = requestAnimationFrame(() => {
    state.streamRenderFrame = null;
    if (state.activeId === conversation.id) {
      renderMessages(conversation);
      renderMessageStats(conversation);
    }
  });
}

async function exportData() {
  await persistNow().catch(reportPersistenceError);
  const content = JSON.stringify({ ...snapshot(), exportedAt: nowIso() }, null, 2);
  const blob = new Blob([content], { type: "application/json" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `zhida-backup-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
}

async function importData(file) {
  try {
    const parsed = JSON.parse(await file.text());
    const imported = normalizeConversations(Array.isArray(parsed) ? parsed : parsed.conversations);
    if (!Array.isArray(parsed) && parsed.version !== SNAPSHOT_VERSION) {
      throw new Error("不支持的数据版本");
    }
    if (!window.confirm(`导入将覆盖当前 ${state.conversations.length} 个对话，是否继续？`)) return;
    state.conversations = imported;
    if (!state.conversations.length) {
      newConversation();
      await persistNow();
      return;
    }
    state.activeId = state.conversations[0].id;
    resetMessageTools();
    await persistNow();
    elements.notice.textContent = `已导入 ${state.conversations.length} 个对话。`;
    render();
  } catch (error) {
    elements.notice.textContent = `导入失败：${error.message}`;
  } finally {
    elements.importFile.value = "";
  }
}

elements.provider.addEventListener("change", () => {
  if (state.sending) return;
  const conversation = activeConversation();
  const provider = selectedProvider();
  if (!conversation || !provider) return;
  conversation.provider = provider.id;
  conversation.model = provider.model;
  touchConversation(conversation);
  save();
  render();
});

elements.model.addEventListener("change", () => {
  if (state.sending) return;
  const conversation = activeConversation();
  conversation.model = elements.model.value.trim();
  touchConversation(conversation);
  save();
});

elements.form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (state.sending) return;
  const content = elements.question.value.trim();
  if (!content) return;
  await startNewTurn(content);
});

elements.question.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    elements.form.requestSubmit();
  }
});

document.querySelector("#new-chat").addEventListener("click", newConversation);
document.querySelector("#menu-button").addEventListener("click", openSidebar);
elements.backdrop.addEventListener("click", closeSidebar);
elements.stop.addEventListener("click", () => state.abortController?.abort());
elements.search.addEventListener("input", () => {
  state.searchQuery = elements.search.value.trim();
  state.editingMessageId = null;
  renderMessages(activeConversation());
  renderMessageStats(activeConversation());
});
elements.clearMessages.addEventListener("click", clearMessages);
elements.exportData.addEventListener("click", () => exportData().catch((error) => {
  elements.notice.textContent = `导出失败：${error.message}`;
}));
elements.importData.addEventListener("click", () => elements.importFile.click());
elements.importFile.addEventListener("change", () => {
  const [file] = elements.importFile.files;
  if (file) importData(file);
});

(async function init() {
  try {
    await Promise.all([loadProviders(), loadConversations()]);
    if (!state.conversations.length) {
      newConversation();
      await persistNow();
    } else {
      state.activeId = state.conversations[0].id;
      render();
    }
  } catch (error) {
    elements.notice.textContent = error.message;
  }
})();
