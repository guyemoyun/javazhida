const state = {
  providers: [],
  conversations: JSON.parse(localStorage.getItem("zhida-conversations") || "[]"),
  activeId: null,
  sending: false,
  editingMessageId: null,
  searchQuery: ""
};

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
  clearMessages: document.querySelector("#clear-messages")
};

function normalizeConversations() {
  let changed = false;
  state.conversations.forEach((conversation) => {
    if (!Array.isArray(conversation.messages)) {
      conversation.messages = [];
      changed = true;
    }
    conversation.messages.forEach((message) => {
      if (!message.id) {
        message.id = crypto.randomUUID();
        changed = true;
      }
    });
  });
  if (changed) save();
}

function save() {
  localStorage.setItem("zhida-conversations", JSON.stringify(state.conversations));
}

function activeConversation() {
  return state.conversations.find((conversation) => conversation.id === state.activeId);
}

function newConversation() {
  const defaultProvider = state.providers[0];
  const conversation = {
    id: crypto.randomUUID(),
    title: "新建对话",
    messages: [],
    provider: defaultProvider?.id || "",
    model: defaultProvider?.model || ""
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
    button.addEventListener("click", () => {
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
    remove.setAttribute("aria-label", `删除对话：${conversation.title}`);
    remove.addEventListener("click", () => deleteConversation(conversation.id));
    row.append(button, remove);
    elements.list.append(row);
  });
}

function deleteConversation(id) {
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
    welcome.innerHTML = "<h2>开始一个问题</h2><p>选择已配置的服务商与模型，然后输入问题。模型密钥仅由服务端读取。</p>";
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
  visibleMessages.forEach((message) => addMessageElement(message));
  if (!query) elements.messages.scrollTop = elements.messages.scrollHeight;
}

function addMessageElement(message) {
  const row = document.createElement("article");
  row.className = `message ${message.role}`;
  row.dataset.messageId = message.id;
  const avatar = document.createElement("div");
  avatar.className = "avatar";
  avatar.textContent = message.role === "user" ? "你" : "AI";
  const content = document.createElement("div");
  content.className = "message-body";
  const meta = document.createElement("div");
  meta.className = "message-meta";
  const metaLabel = document.createElement("span");
  metaLabel.textContent = message.role === "user" ? "你的问题" : `${message.provider || "模型"} · ${message.model || ""}`;
  const actions = document.createElement("span");
  actions.className = "message-actions";
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
    appendHighlightedText(text, message.content, state.searchQuery);
    content.append(meta, text);
  }
  row.append(avatar, content);
  elements.messages.append(row);
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
  input.maxLength = 12000;
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
  state.editingMessageId = null;
  elements.notice.textContent = "";
  syncConversationTitle(conversation);
  save();
  render();
}

function deleteMessage(id) {
  if (state.sending || state.editingMessageId !== null) return;
  const conversation = activeConversation();
  conversation.messages = conversation.messages.filter((message) => message.id !== id);
  syncConversationTitle(conversation);
  save();
  render();
}

function clearMessages() {
  const conversation = activeConversation();
  if (!conversation.messages.length || state.sending) return;
  if (!window.confirm("确定要删除当前对话中的所有消息吗？此操作无法撤销。")) return;
  conversation.messages = [];
  conversation.title = "新建对话";
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
  if (elements.search) elements.search.value = "";
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
  const configured = provider && provider.configured;
  elements.send.disabled = !configured || state.sending;
  elements.notice.textContent = configured ? "" : "请先在 config/model-config.properties 中填写该服务商的 API_KEY，然后重新启动项目。";
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

elements.provider.addEventListener("change", () => {
  const conversation = activeConversation();
  const provider = selectedProvider();
  conversation.provider = provider.id;
  conversation.model = provider.model;
  save();
  render();
});

elements.model.addEventListener("change", () => {
  const conversation = activeConversation();
  conversation.model = elements.model.value.trim();
  save();
});

elements.form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const content = elements.question.value.trim();
  const conversation = activeConversation();
  const provider = selectedProvider();
  if (!content || !provider || !provider.configured) return;

  const history = conversation.messages
    .slice(-MAX_HISTORY_MESSAGES)
    .map(({ role, content: previousContent }) => ({ role, content: previousContent }));
  const userMessage = { id: crypto.randomUUID(), role: "user", content };
  conversation.messages.push(userMessage);
  if (conversation.title === "新建对话") conversation.title = content.slice(0, 28);
  conversation.provider = provider.id;
  conversation.model = elements.model.value.trim();
  elements.question.value = "";
  state.sending = true;
  elements.status.textContent = "模型正在回答...";
  save();
  render();

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ provider: provider.id, model: conversation.model, message: content, history })
    });
    const payload = await response.json();
    if (!response.ok || payload.code !== 200) throw new Error(payload.msg || "模型请求失败");
    conversation.messages.push({ id: crypto.randomUUID(), role: "assistant", content: payload.data.message, provider: payload.data.provider, model: payload.data.model });
    save();
    render();
  } catch (error) {
    elements.notice.textContent = error.message;
  } finally {
    state.sending = false;
    elements.send.disabled = !selectedProvider()?.configured;
    elements.clearMessages.disabled = !conversation.messages.length;
    elements.messages.querySelectorAll(".message-actions button").forEach((button) => {
      button.disabled = false;
    });
    elements.status.textContent = "Enter 发送，Shift + Enter 换行";
  }
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
elements.search.addEventListener("input", () => {
  state.searchQuery = elements.search.value.trim();
  state.editingMessageId = null;
  renderMessages(activeConversation());
  renderMessageStats(activeConversation());
});
elements.clearMessages.addEventListener("click", clearMessages);

(async function init() {
  try {
    normalizeConversations();
    await loadProviders();
    if (!state.conversations.length) newConversation();
    else { state.activeId = state.conversations[0].id; render(); }
  } catch (error) {
    elements.notice.textContent = error.message;
  }
})();
