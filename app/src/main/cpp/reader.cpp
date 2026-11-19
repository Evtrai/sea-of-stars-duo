// Sea of Stars Duo: in-process game state reader.
//
// libil2cpp.so is loaded by the hosted UnityPlayer in another linker namespace, so its
// il2cpp_* exports are resolved by parsing the ELF on disk + the base from /proc/self/maps.
// Raw reads go through process_vm_readv on our own pid (bad pointer -> failed read, no crash).
// Managed calls (localization, stat getters) run on this thread after il2cpp_thread_attach,
// with exceptions captured. Output: one JSON snapshot string, polled by Java.
//
// Field and method names come from the Il2CppDumper dump (see NOTES.md).

#include <cmath>
#include <sys/stat.h>
#include <algorithm>
#include <jni.h>
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/uio.h>
#include <pthread.h>
#include <time.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <mutex>
#include <vector>
#include <unordered_map>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SosDuo", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SosDuo", __VA_ARGS__)

namespace {

typedef uintptr_t P;  // managed pointer

std::mutex g_mu;
std::string g_json = "{\"state\":\"boot\",\"msg\":\"Waiting for the game...\"}";
void publish(const std::string& s) { std::lock_guard<std::mutex> l(g_mu); g_json = s; }
void publishMsg(const char* state, const char* msg) {
    publish(std::string("{\"state\":\"") + state + "\",\"msg\":\"" + msg + "\"}");
}

// ---------------------------------------------------------------- safe memory
bool safeRead(P addr, void* out, size_t n) {
    if (addr < 0x10000) return false;
    iovec local{out, n}, remote{reinterpret_cast<void*>(addr), n};
    return process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == (ssize_t)n;
}
template <class T> T rd(P a, bool* ok = nullptr) { T v{}; bool r = safeRead(a, &v, sizeof v); if (ok) *ok = r; return v; }

// ---------------------------------------------------------------- ELF exports
bool findLib(const char* name, P& base, std::string& path) {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[1024];
    base = 0;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, name)) continue;
        unsigned long start, offset; char perms[8];
        if (sscanf(line, "%lx-%*lx %7s %lx", &start, perms, &offset) != 3 || offset != 0) continue;
        char* p = strchr(line, '/');
        if (!p) continue;
        p[strcspn(p, "\n")] = 0;
        base = start; path = p;
        break;
    }
    fclose(f);
    return base != 0;
}

std::unordered_map<std::string, P> loadExports(const std::string& path, P base) {
    std::unordered_map<std::string, P> out;
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return out;
    Elf64_Ehdr eh{}; pread(fd, &eh, sizeof eh, 0);
    std::vector<Elf64_Phdr> ph(eh.e_phnum); pread(fd, ph.data(), ph.size() * sizeof(Elf64_Phdr), eh.e_phoff);
    P minV = UINTPTR_MAX;
    for (auto& p : ph) if (p.p_type == PT_LOAD && p.p_vaddr < minV) minV = p.p_vaddr & ~0xfffUL;
    std::vector<Elf64_Shdr> sh(eh.e_shnum); pread(fd, sh.data(), sh.size() * sizeof(Elf64_Shdr), eh.e_shoff);
    for (auto& s : sh) {
        if (s.sh_type != SHT_DYNSYM || s.sh_link >= sh.size()) continue;
        auto& ss = sh[s.sh_link];
        std::vector<char> strtab(ss.sh_size); pread(fd, strtab.data(), strtab.size(), ss.sh_offset);
        std::vector<Elf64_Sym> syms(s.sh_size / sizeof(Elf64_Sym)); pread(fd, syms.data(), syms.size() * sizeof(Elf64_Sym), s.sh_offset);
        for (auto& y : syms)
            if (y.st_value && y.st_name < strtab.size() && !strncmp(strtab.data() + y.st_name, "il2cpp_", 7))
                out[strtab.data() + y.st_name] = base - minV + y.st_value;
    }
    close(fd);
    return out;
}

// ---------------------------------------------------------------- il2cpp API
struct Api {
    void* (*get_corlib)();
    void* (*domain_get)();
    void* (*thread_attach)(void*);
    void** (*domain_get_assemblies)(void*, size_t*);
    void* (*assembly_get_image)(void*);
    const char* (*image_get_name)(void*);
    void* (*class_from_name)(void*, const char*, const char*);
    void* (*class_get_parent)(void*);
    void* (*class_get_field_from_name)(void*, const char*);
    void* (*class_get_method_from_name)(void*, const char*, int);
    size_t (*field_get_offset)(void*);
    void (*field_static_get_value)(void*, void*);
    const char* (*class_get_name)(void*);
    int (*class_array_element_size)(void*);
    void* (*class_get_element_class)(void*);
    void* (*runtime_invoke)(void*, void*, void**, void**);
    void* (*object_unbox)(void*);
    void* (*string_new)(const char*);
    void* (*class_get_fields)(void*, void**);
    int (*field_get_flags)(void*);
    void* (*field_get_type)(void*);
    int (*type_get_type)(void*);
    void* (*class_from_type)(void*);
    bool (*class_is_subclass_of)(void*, void*, bool);
    void* (*object_new)(void*);
    void* (*class_get_methods)(void*, void**);
    unsigned (*method_get_param_count)(void*);
    void* (*method_get_param)(void*, unsigned);
    char* (*type_get_name)(void*);
    const char* (*method_get_name)(void*);
    void* (*class_get_type)(void*);
    void* (*type_get_object)(void*);
    void* (*resolve_icall)(const char*);   // optional
} api;

// One lock for everything that touches our caches / il2cpp helpers: the reader thread and
// Unity's main thread (via the Update hook) both use them.
std::recursive_mutex g_api;

template <class F> bool bind(std::unordered_map<std::string, P>& m, const char* n, F& f) {
    auto it = m.find(n);
    if (it == m.end()) { LOGE("missing export %s", n); return false; }
    f = reinterpret_cast<F>(it->second);
    return true;
}

void* g_image = nullptr;  // Assembly-CSharp
std::vector<void*> g_images;  // all loaded assemblies

// ---------------------------------------------------------------- object helpers
P klassOf(P obj) { return obj ? rd<P>(obj) : 0; }
std::string className(P obj) { P k = klassOf(obj); return k ? api.class_get_name((void*)k) : ""; }

std::unordered_map<std::string, int> g_off;
int fieldOff(P klass, const char* name) {
    if (!klass) return -1;
    std::string key = std::to_string(klass) + ":" + name;
    auto it = g_off.find(key);
    if (it != g_off.end()) return it->second;
    void* f = api.class_get_field_from_name((void*)klass, name);
    int o = f ? (int)api.field_get_offset(f) : -1;
    g_off[key] = o;
    return o;
}
P ptrF(P obj, const char* n) { if (!obj) return 0; int o = fieldOff(klassOf(obj), n); return o < 0 ? 0 : rd<P>(obj + o); }
template <class T> T valF(P obj, const char* n, T dflt = T{}) {
    if (!obj) return dflt;
    int o = fieldOff(klassOf(obj), n);
    if (o < 0) return dflt;
    bool ok; T v = rd<T>(obj + o, &ok);
    return ok ? v : dflt;
}
P fieldAddr(P obj, const char* n) { if (!obj) return 0; int o = fieldOff(klassOf(obj), n); return o < 0 ? 0 : obj + o; }

// System.String -> UTF-8
std::string str(P s) {
    if (!s) return "";
    int len = rd<int>(s + 0x10);
    if (len <= 0 || len > 4096) return "";
    std::vector<uint16_t> w(len);
    if (!safeRead(s + 0x14, w.data(), len * 2)) return "";
    std::string out;
    for (int i = 0; i < len; i++) {
        uint32_t c = w[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < len) { c = 0x10000 + ((c - 0xD800) << 10) + (w[i + 1] - 0xDC00); i++; }
        if (c < 0x80) out += (char)c;
        else if (c < 0x800) { out += (char)(0xC0 | (c >> 6)); out += (char)(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { out += (char)(0xE0 | (c >> 12)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
        else { out += (char)(0xF0 | (c >> 18)); out += (char)(0x80 | ((c >> 12) & 0x3F)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
    }
    return out;
}

// Element stride of an array object (il2cpp_class_array_element_size takes the ELEMENT class).
int elemSize(P arrObj) { return api.class_array_element_size(api.class_get_element_class((void*)klassOf(arrObj))); }

// List<T>: element addresses
std::vector<P> listElems(P list) {
    std::vector<P> v;
    P items = ptrF(list, "_items");
    int size = valF<int>(list, "_size");
    if (!items || size <= 0 || size > 4096) return v;
    int es = elemSize(items);
    for (int i = 0; i < size; i++) v.push_back(items + 0x20 + (P)i * es);
    return v;
}
std::vector<P> listRefs(P list) { std::vector<P> v; for (P a : listElems(list)) v.push_back(rd<P>(a)); return v; }

// Dictionary<K,V>: (keyAddr, valueAddr) of live entries
struct KV { P key, val; };
std::vector<KV> dictEntries(P dict) {
    std::vector<KV> v;
    P entries = ptrF(dict, "_entries");
    int count = valF<int>(dict, "_count");
    if (!entries || count <= 0 || count > 100000) return v;
    P arrK = klassOf(entries);
    int es = elemSize(entries);
    P entK = (P)api.class_get_element_class((void*)arrK);
    int ko = fieldOff(entK, "key") - 0x10, vo = fieldOff(entK, "value") - 0x10, ho = fieldOff(entK, "hashCode") - 0x10;
    static int dlog = 0;
    if (dlog++ < 8) LOGI("dict %s count=%d es=%d ko=%d vo=%d ho=%d entK=%s", className(dict).c_str(), count, es, ko, vo, ho, api.class_get_name((void*)entK));
    if (ko < 0 || vo < 0) return v;
    for (int i = 0; i < count; i++) {
        P e = entries + 0x20 + (P)i * es;
        if (ho >= 0 && rd<int>(e + ho) < 0) continue;  // free slot
        v.push_back({e + ko, e + vo});
    }
    return v;
}

// ---------------------------------------------------------------- managed calls
std::unordered_map<std::string, void*> g_methods;
void* method(P klass, const char* name, int argc) {
    std::string key = std::to_string(klass) + ":" + name + "/" + std::to_string(argc);
    auto it = g_methods.find(key);
    if (it != g_methods.end()) return it->second;
    void* m = klass ? api.class_get_method_from_name((void*)klass, name, argc) : nullptr;
    g_methods[key] = m;
    return m;
}
// Returns the raw result object (boxed for value types) or 0 on failure/exception.
P invoke(P obj, P klass, const char* name, int argc, void** args) {
    void* m = method(klass ? klass : klassOf(obj), name, argc);
    if (!m) return 0;
    void* exc = nullptr;
    void* r = api.runtime_invoke(m, (void*)obj, args, &exc);
    if (exc) return 0;
    return (P)r;
}
// true if the method exists and returned without a managed exception
bool invokeOk(P obj, const char* name, int argc, void** args) {
    void* m = method(klassOf(obj), name, argc);
    if (!m) return false;
    void* exc = nullptr;
    api.runtime_invoke(m, (void*)obj, args, &exc);
    if (exc) LOGE("exception in %s: %s %s", name, className((P)exc).c_str(), str(ptrF((P)exc, "_message")).c_str());
    return !exc;
}
int invokeInt(P obj, const char* name, int argc, void** args, int dflt = -1) {
    P r = invoke(obj, 0, name, argc, args);
    return r ? *(int*)api.object_unbox((void*)r) : dflt;
}

// name may be namespace-qualified ("Sabotage.Localization.LocalizationManager")
void* findClass(const char* full) {
    std::string f = full, ns, name = full;
    size_t dot = f.rfind('.');
    if (dot != std::string::npos) { ns = f.substr(0, dot); name = f.substr(dot + 1); }
    void* c = g_image ? api.class_from_name(g_image, ns.c_str(), name.c_str()) : nullptr;
    for (size_t i = 0; !c && i < g_images.size(); i++) c = api.class_from_name(g_images[i], ns.c_str(), name.c_str());
    static std::unordered_map<std::string, bool> warned;
    if (!c && !warned[f]) { warned[f] = true; LOGE("class %s not found", full); }
    return c;
}
P managerInstance(const char* name) {
    static std::unordered_map<std::string, void*> fields;
    void*& f = fields[name];
    if (!f) {
        void* c = findClass(name);
        void* parent = c ? api.class_get_parent(c) : nullptr;   // Manager<T>
        f = parent ? api.class_get_field_from_name(parent, "instance") : nullptr;
        if (!f) return 0;
    }
    P inst = 0;
    api.field_static_get_value(f, &inst);
    return inst;
}

// LocalizationId {string categoryName; string locId} -> text, cached
std::unordered_map<std::string, std::string> g_loc;
std::string loc(P locIdAddr) {
    if (!locIdAddr) return "";
    P cat = rd<P>(locIdAddr), id = rd<P>(locIdAddr + 8);
    std::string sc = str(cat), si = str(id);
    if (si.empty()) return "";
    std::string key = sc + "/" + si;
    auto it = g_loc.find(key);
    if (it != g_loc.end()) return it->second;
    P lm = managerInstance("Sabotage.Localization.LocalizationManager");
    std::string text;
    if (lm) {
        void* args[4] = {(void*)cat, (void*)id, nullptr, nullptr};
        P ms = invoke(lm, 0, "GetText", 4, args);
        // The game runs every localized string through its IReplaceVariableLocalizers
        // (character names etc. are variables like MOONGIRL_NAME). Do the same: ReplaceVariables(ref string).
        if (ms) {
            for (P rv : listRefs(ptrF(lm, "replaceVariableLocalizers"))) {
                if (!rv) continue;
                P slot = ms;
                void* ra[1] = {&slot};
                invoke(rv, 0, "ReplaceVariables", 1, ra);
                if (slot) ms = slot;
            }
        }
        text = str(ms);
    }
    // Don't cache failures/placeholders forever: categories load lazily.
    if (!text.empty() && text[0] != '[' && text.find("MISSING") == std::string::npos) g_loc[key] = text;
    return text.empty() ? si : text;
}

// ---------------------------------------------------------------- JSON helpers
std::string esc(const std::string& s) {
    std::string o;
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': break;
            case '\t': o += ' '; break;
            default: if ((unsigned char)c >= 0x20) o += c;
        }
    }
    return o;
}
std::string q(const std::string& s) { return "\"" + esc(s) + "\""; }
std::string kv(const char* k, const std::string& v) { return std::string("\"") + k + "\":" + q(v); }
std::string kvi(const char* k, long v) { return std::string("\"") + k + "\":" + std::to_string(v); }
std::string kvb(const char* k, bool v) { return std::string("\"") + k + "\":" + (v ? "true" : "false"); }

const char* damageName(int t) {
    switch (t) {
        case 2: return "Sword"; case 4: return "Sun"; case 8: return "Moon"; case 16: return "Eclipse";
        case 32: return "Poison"; case 64: return "Arcane"; case 128: return "Stun"; case 256: return "Blunt";
        default: return "Any";
    }
}

// defined in the main-thread layer below
void requestPortrait(const std::string& id, P def);
std::string iconOf(P sprite);
std::string menuJson(P enc);

// ---------------------------------------------------------------- snapshot pieces
std::string itemName(P item) { return item ? loc(fieldAddr(item, "nameLocalizationId")) : ""; }

std::string partyJson(std::unordered_map<std::string, int>& manaById) {
    P ppm = managerInstance("PlayerPartyManager");
    P csm = managerInstance("CharacterStatsManager");
    P cdm = managerInstance("CharacterDefinitionManager");
    if (!ppm || !csm) return "[]";
    std::string out = "[";
    bool first = true;
    for (P idAddr : listElems(ptrF(ppm, "currentParty"))) {
        std::string id = str(rd<P>(idAddr));
        if (id.empty()) continue;
        void* a1[1] = {(void*)idAddr};
        P data = invoke(csm, 0, "GetCharacterData", 1, a1);
        P def = cdm ? invoke(cdm, 0, "GetCharacterDefinition", 1, a1) : 0;
        std::string name = def ? loc(fieldAddr(def, "nameLocId")) : id;
        if (def) requestPortrait(id, def);
        std::string title = def ? loc(fieldAddr(def, "titleLocId")) : "";
        char color[16] = "#5fe3e0";
        if (def) {
            P ca = fieldAddr(def, "characterColor");
            float c[4]; if (ca && safeRead(ca, c, sizeof c))
                snprintf(color, sizeof color, "#%02x%02x%02x", (int)(c[0] * 255) & 255, (int)(c[1] * 255) & 255, (int)(c[2] * 255) & 255);
        }
        auto stat = [&](int s) { int m = -1; void* a[2] = {&s, &m}; return invokeInt(data, "GetStat", 2, a, 0); };
        bool f = false; int zero = 0; void* la[2] = {&f, &zero};
        int plv = invokeInt(csm, "GetLevel", 2, la, 0);
        std::string row = "{" + kv("id", id) + "," + kv("name", name) + "," + kv("title", title) + "," + kv("color", color);
        // portrait and map head straight from the character's definition (the HUD widgets are not always there, e.g. at sea)
        if (def) {
            row += "," + kv("face", iconOf(ptrF(def, "combatPortrait"))) + "," + kv("mapIcon", iconOf(ptrF(def, "mapIcon")));
            // the big portrait of the game's Equip card: exactly what its card asks for
            // (GameMenuCharacterSectionCharacterInfo.RefreshCharacterInfo -> CharacterDefinitionManager.GetPortrait),
            // which also covers characters whose portrait changes with the story
            P ps = invoke(cdm, 0, "GetPortrait", 1, a1);
            if (ps && className(ps) == "Sprite") row += "," + kv("portrait", iconOf(ps));
        }
        if (data) {
            row += "," + kvi("lv", plv > 0 ? plv : stat(7)) + "," + kvi("hp", valF<int>(data, "currentHP")) + "," + kvi("hpMax", stat(0)) +
                   "," + kvi("mp", valF<int>(data, "currentSP")) + "," + kvi("mpMax", stat(1)) +
                   "," + kvi("atk", stat(3)) + "," + kvi("def", stat(4)) + "," + kvi("matk", stat(5)) + "," + kvi("mdef", stat(6)) +
                   "," + kv("weapon", itemName(ptrF(data, "equippedWeapon"))) + "," + kv("armor", itemName(ptrF(data, "equippedArmor")));
            std::string tr = "[";
            bool f2 = true;
            for (P t : listRefs(ptrF(data, "equippedTrinkets"))) { if (!t) continue; tr += (f2 ? "" : ",") + q(itemName(t)); f2 = false; }
            row += ",\"trinkets\":" + tr + "]";
        }
        auto mi = manaById.find(id);
        row += "," + kvi("mana", mi == manaById.end() ? -1 : mi->second) + "}";
        out += (first ? "" : ",") + row;
        first = false;
    }
    return out + "]";
}

std::string battleJson(P enc, std::unordered_map<std::string, int>& manaById) {
    // party live mana
    for (P slot : listRefs(ptrF(enc, "playerSlots"))) {
        P actor = ptrF(slot, "currentActor");
        if (!actor) continue;
        std::string id = str(rd<P>(fieldAddr(actor, "characterDefinitionId")));
        P lmh = ptrF(actor, "liveManaHandler");
        if (!id.empty() && lmh) manaById[id] = valF<int>(lmh, "manaChargeCount");
    }
    std::string out = "[";
    bool first = true;
    for (P slot : listRefs(ptrF(enc, "enemySlots"))) {
        P e = ptrF(slot, "enemy");
        if (!e) continue;
        P data = ptrF(e, "enemyData");
        auto tl = listRefs(ptrF(e, "targets"));
        P target = tl.empty() ? 0 : tl[0];
        P tdata = ptrF(target, "targetData");
        std::string name = loc(fieldAddr(tdata, "nameLocId"));
        if (name.empty()) name = loc(fieldAddr(data, "nameLocalizationId"));
        int hp = valF<int>(target, "currentHP", -1);
        int hpMax = valF<int>(tdata, "hp", 0);
        if (hpMax <= 0) hpMax = valF<int>(data, "hp", 0);
        P cd = ptrF(e, "castingData");
        std::string locks = "[";
        P lockList = ptrF(cd, "spellLocks");
        P items = ptrF(lockList, "_items");
        int nLocks = valF<int>(lockList, "_size");
        if (items && nLocks > 0 && nLocks < 32) {
            P arrK = klassOf(items);
            int es = elemSize(items);
            P elK = (P)api.class_get_element_class((void*)arrK);
            int to = fieldOff(elK, "damageType") - 0x10, ro = fieldOff(elK, "revealed") - 0x10;
            for (int i = 0; i < nLocks && to >= 0 && ro >= 0; i++) {
                P el = items + 0x20 + (P)i * es;
                P dt = rd<P>(el + to);
                int t = valF<int>(dt, "damageType", 1);
                locks += std::string(i ? "," : "") + "{" + kv("t", damageName(t)) + "," + kvb("r", rd<bool>(el + ro)) + "}";
            }
        }
        locks += "]";
        std::string row = "{" + kv("name", name) + "," + kvi("hp", hp) + "," + kvi("hpMax", hpMax) +
                          "," + kvb("hideHP", valF<bool>(e, "hideHP")) + "," + kvb("dead", valF<bool>(e, "dead")) +
                          "," + kvi("turns", valF<int>(cd, "turnsToAction", -1)) + "," + kvi("totalLocks", valF<int>(cd, "totalSpellLocks")) +
                          ",\"locks\":" + locks + "}";
        out += (first ? "" : ",") + row;
        first = false;
    }
    return out + "]";
}

std::string g_itemsJson = "[]";
long g_itemsAt = 0;
std::string itemsJson() {
    P im = managerInstance("InventoryManager");
    if (!im) return "[]";
    std::string out = "[";
    bool first = true;
    for (auto& e : dictEntries(ptrF(im, "allInventoryItemData"))) {
        void* a[1] = {(void*)e.key};            // InventoryItemReference by value
        int qty = invokeInt(im, "GetOwnedQuantity", 1, a, 0);
        if (qty <= 0) continue;
        P item = rd<P>(e.val);
        if (!item) continue;
        std::string row = "{" + kv("name", itemName(item)) + "," + kvi("qty", qty) + "," + kv("cat", className(item)) + "," +
                          kv("desc", loc(fieldAddr(item, "descriptionLocalizationId"))) + "}";
        out += (first ? "" : ",") + row;
        first = false;
    }
    return out + "]";
}

long nowMs() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000 + t.tv_nsec / 1000000; }

// Battle extras, built on Unity's main thread (battleExtras) and read by the reader thread under g_api.
std::string g_bx;                                   // JSON object text, "" outside battles
std::unordered_map<P, std::string> g_moveTypes;     // PlayerCombatMoveDefinition -> ["Sun","Sword"]

std::string g_tools = "{}";                         // which of the game's own tool buttons are currently offered
std::string g_faces = "{}";                         // character id -> sprite name used by the game's HUD (main thread)

std::string snapshot() {
    P cm = managerInstance("CombatManager");
    P enc = ptrF(cm, "currentEncounter");
    std::unordered_map<std::string, int> mana;
    std::string enemies = enc ? battleJson(enc, mana) : "[]";
    std::string menu = enc ? menuJson(enc) : "null";

    P csm = managerInstance("CharacterStatsManager");
    std::string s = "{" + kv("state", enc ? "battle" : "field");
    if (enc) s += "," + kvb("boss", valF<bool>(enc, "bossEncounter"));
    s += "," + kvi("combo", valF<int>(csm, "comboPoints")) + "," + kvi("comboUnit", 1000) + "," + kvi("comboMax", valF<int>(csm, "maxComboPoints")) +
         "," + kvi("ult", valF<int>(csm, "ultPoints"));

    // gold
    P cur = managerInstance("CurrencyManager");
    long gold = -1;
    for (auto& e : dictEntries(ptrF(cur, "currencyQuantities"))) { gold = rd<int>(e.val); break; }
    s += "," + kvi("gold", gold);
    // party level and progress to the next one (the LEVEL badge of the game's menu bar, UIXPBar)
    if (csm) {
        bool f = false; int zero = 0; void* la[2] = {&f, &zero};
        s += "," + kvi("plv", invokeInt(csm, "GetLevel", 2, la, 0)) + "," + kvi("xpRem", invokeInt(csm, "GetXPRemainingToNextLevel", 0, nullptr, -1)) +
             "," + kvi("xpReq", invokeInt(csm, "GetXPRequiredToLevelUp", 0, nullptr, -1));
    }

    // area
    P lvm = managerInstance("LevelManager");
    std::string area;
    if (lvm) {
        P curLevel = fieldAddr(lvm, "currentLevel");
        void* a[1] = {(void*)curLevel};
        P def = invoke(lvm, 0, "GetLevelDefinition", 1, a);
        area = loc(fieldAddr(def, "levelNameLocId"));
    }
    s += "," + kv("area", area) + "," + kv("level", lvm ? str(rd<P>(fieldAddr(lvm, "currentLevel"))) : "");
    extern float g_leaderPos[3]; extern bool g_leaderOk;
    if (g_leaderOk) { char b[96]; snprintf(b, sizeof b, ",\"pos\":[%.2f,%.2f,%.2f]", g_leaderPos[0], g_leaderPos[1], g_leaderPos[2]); s += b; }

    P prog = managerInstance("ProgressionManager");
    s += "," + kvi("playtime", (long)valF<double>(prog, "playTime"));

    s += ",\"party\":" + partyJson(mana);
    s += ",\"enemies\":" + enemies + ",\"menu\":" + menu;
    if (enc && !g_bx.empty()) s += ",\"bx\":" + g_bx;
    s += ",\"faces\":" + g_faces + ",\"tools\":" + g_tools;
    { extern int g_mdSeq; s += "," + kvi("mdSeq", g_mdSeq); }
    s += "," + kvb("wm", className(ptrF(managerInstance("PlayerPartyManager"), "leader")).find("WorldMap") != std::string::npos);
    s += "," + kvb("camp", valF<bool>(managerInstance("CampingManager"), "isCamping"));
    long t = nowMs();
    if (t - g_itemsAt > 3000) { g_itemsJson = itemsJson(); g_itemsAt = t; }
    s += ",\"items\":" + g_itemsJson + "}";
    return s;
}


// ================================================================ main-thread layer
// Unity calls MonoBehaviour.Update through MethodInfo->methodPointer, so pointing InputManager.Update's
// MethodInfo at our function gives us a per-frame callback on Unity's main thread.

// exact-overload lookup: param type names, e.g. {"System.Int32","UnityEngine.TextureFormat"}
void* methodSig(void* klass, const char* name, std::vector<const char*> params) {
    std::string key = std::to_string((P)klass) + ":" + name + "(";
    for (auto t : params) key += std::string(t) + ",";
    static std::unordered_map<std::string, void*> cache;
    auto it = cache.find(key);
    if (it != cache.end()) return it->second;
    void* found = nullptr;
    void* iter = nullptr;
    while (void* m = api.class_get_methods(klass, &iter)) {
        if (strcmp(api.method_get_name(m), name) || api.method_get_param_count(m) != params.size()) continue;
        bool ok = true;
        for (unsigned i = 0; i < params.size() && ok; i++) {
            char* tn = api.type_get_name(api.method_get_param(m, i));
            ok = tn && !strcmp(tn, params[i]);
        }
        if (ok) { found = m; break; }
    }
    if (!found) LOGE("method %s not found", key.c_str());
    cache[key] = found;
    return found;
}
P call(void* m, P obj, void** args) {
    if (!m) return 0;
    void* exc = nullptr;
    void* r = api.runtime_invoke(m, (void*)obj, args, &exc);
    if (exc) { LOGE("exception in %s", api.method_get_name(m)); return 0; }
    return (P)r;
}

// ---- portraits: Sprite -> RGBA (bottom-up rows), keyed by character id
struct Portrait { int w = 0, h = 0; std::vector<uint8_t> rgba; };
std::mutex g_portMu;
std::unordered_map<std::string, Portrait> g_portraits;       // done (w<=1 means failed)
std::vector<std::pair<std::string, P>> g_portraitWanted;     // id -> Sprite* (queued by reader thread)

bool readSprite(P sprite, Portrait& out) {
    void* cSprite = findClass("UnityEngine.Sprite");
    void* cRT = findClass("UnityEngine.RenderTexture");
    void* cT2D = findClass("UnityEngine.Texture2D");
    void* cGfx = findClass("UnityEngine.Graphics");
    void* cObj = findClass("UnityEngine.Object");
    if (!cSprite || !cRT || !cT2D || !cGfx || !cObj) return false;
    P tex = call(methodSig(cSprite, "get_texture", {}), sprite, nullptr);
    P rectBox = call(methodSig(cSprite, "get_textureRect", {}), sprite, nullptr);
    if (!tex || !rectBox) return false;
    float rect[4];
    memcpy(rect, api.object_unbox((void*)rectBox), sizeof rect);   // x, y, w, h (pixels, bottom-left origin)
    int tw = invokeInt(tex, "get_width", 0, nullptr, 0), th = invokeInt(tex, "get_height", 0, nullptr, 0);
    int rw = (int)rect[2], rh = (int)rect[3];
    if (tw <= 0 || th <= 0 || rw <= 0 || rh <= 0 || rw > 1024 || rh > 1024) return false;

    // RenderTexture(w, h, depth, GraphicsFormat.R8G8B8A8_SRGB = 4)
    P rt = (P)api.object_new(cRT);
    int depth = 0, gfmt = 4;
    void* a1[4] = {&tw, &th, &depth, &gfmt};
    call(methodSig(cRT, ".ctor", {"System.Int32", "System.Int32", "System.Int32", "UnityEngine.Experimental.Rendering.GraphicsFormat"}), rt, a1);
    void* a2[2] = {(void*)tex, (void*)rt};
    call(methodSig(cGfx, "Blit", {"UnityEngine.Texture", "UnityEngine.RenderTexture"}), 0, a2);
    P prev = call(methodSig(cRT, "get_active", {}), 0, nullptr);
    void* a3[1] = {(void*)rt};
    call(methodSig(cRT, "set_active", {"UnityEngine.RenderTexture"}), 0, a3);
    // Texture2D(w, h, TextureFormat.RGBA32 = 4, mipChain = false, linear = false)
    P t2 = (P)api.object_new(cT2D);
    int fmt = 4; bool mip = false, lin = false;
    void* a4[5] = {&rw, &rh, &fmt, &mip, &lin};
    call(methodSig(cT2D, ".ctor", {"System.Int32", "System.Int32", "UnityEngine.TextureFormat", "System.Boolean", "System.Boolean"}), t2, a4);
    int zero = 0;
    void* a5[3] = {rect, &zero, &zero};
    call(methodSig(cT2D, "ReadPixels", {"UnityEngine.Rect", "System.Int32", "System.Int32"}), t2, a5);
    P px = call(methodSig(cT2D, "GetPixels32", {}), t2, nullptr);
    void* a6[1] = {(void*)prev};
    call(methodSig(cRT, "set_active", {"UnityEngine.RenderTexture"}), 0, a6);
    void* d1[1] = {(void*)rt};
    call(methodSig(cObj, "Destroy", {"UnityEngine.Object"}), 0, d1);
    void* d2[1] = {(void*)t2};
    call(methodSig(cObj, "Destroy", {"UnityEngine.Object"}), 0, d2);
    if (!px) return false;
    size_t n = (size_t)rw * rh * 4;
    if ((size_t)rd<int64_t>(px + 0x18) * 4 < n) return false;
    out.w = rw; out.h = rh;
    out.rgba.resize(n);
    return safeRead(px + 0x20, out.rgba.data(), n);
}

// ---- touch actions for the in-battle selector lists
std::mutex g_actMu;
std::vector<std::string> g_actions;

void* g_listBase = nullptr;       // CombatActionSelectorListContent
void* g_listBaseTouch = nullptr;  // CombatActionSelectorListContentTouch

// The focused selector list referenced by the encounter's current state (commands / skills / items).
P activeList(P enc) {
    if (!g_listBase) g_listBase = findClass("CombatActionSelectorListContent");
    if (!g_listBaseTouch) g_listBaseTouch = findClass("CombatActionSelectorListContentTouch");
    P st = ptrF(ptrF(enc, "stateMachine"), "currentState");
    static std::string lastState;
    std::string sc = className(st);
    if (sc != lastState) { lastState = sc; LOGI("encounter state -> %s", sc.c_str()); }
    if (!st) return 0;
    P best = 0;
    for (void* k = (void*)klassOf(st); k; k = api.class_get_parent(k)) {
        void* iter = nullptr;
        while (void* f = api.class_get_fields(k, &iter)) {
            if (api.field_get_flags(f) & 0x10) continue;               // static
            void* t = api.field_get_type(f);
            if (api.type_get_type(t) != 0x12) continue;                  // IL2CPP_TYPE_CLASS only
            void* fc = api.class_from_type(t);
            bool isList = (g_listBase && (fc == g_listBase || api.class_is_subclass_of(fc, g_listBase, false))) ||
                          (g_listBaseTouch && (fc == g_listBaseTouch || api.class_is_subclass_of(fc, g_listBaseTouch, false)));
            if (!isList) continue;
            P v = rd<P>(st + api.field_get_offset(f));
            if (!v) continue;
            if (valF<bool>(v, "hasFocus", true)) return v;
            if (!best) best = v;
        }
    }
    return best;
}

std::string tmpText(P tmp) { return str(ptrF(tmp, "m_text")); }

std::string menuJson(P enc) {
    P list = activeList(enc);
    if (!list) return "null";
    std::string cls = className(list);
    std::string kind = cls.find("Command") != std::string::npos ? "commands" : cls.find("SpecialMove") != std::string::npos ? "skills"
                     : cls.find("Item") != std::string::npos ? "items" : "list";
    P owner = ptrF(list, "owner");
    std::string actor = str(rd<P>(fieldAddr(owner, "characterDefinitionId")));
    std::string out = "{" + kv("kind", kind) + "," + kv("actor", actor) + "," + kvi("sel", valF<int>(list, "selectedItemIndex")) + ",\"items\":[";
    bool first = true;
    for (P it : listRefs(ptrF(list, "items"))) {
        std::string label, sub;
        bool ok = true;
        if (P def = ptrF(it, "battleCommandDefinition")) label = loc(fieldAddr(def, "nameLocId"));
        else if (P f = ptrF(it, "specialMoveNameField")) { label = tmpText(f); sub = tmpText(ptrF(it, "apCostField")); ok = valF<bool>(it, "canCast", true); }
        else if (P f2 = ptrF(it, "nameField")) { label = tmpText(f2); sub = tmpText(ptrF(it, "quantityField")); }
        ok = ok && valF<bool>(it, "interactable", true);   // greyed-out entries (e.g. Combo with no points)
        // commands are greyed by label colour (memory read only; safe off the main thread)
        if (P nf = ptrF(it, "nameField")) {
            P ca = fieldAddr(nf, "m_fontColor");
            if (ca) { float r = rd<float>(ca), g = rd<float>(ca + 4), b = rd<float>(ca + 8), al = rd<float>(ca + 12); if ((r + g + b) / 3 < 0.62f || al < 0.6f) ok = false; }
        }
        std::string extra;
        if (P snack = ptrF(it, "snack")) extra = "," + kv("desc", loc(fieldAddr(snack, "descriptionLocalizationId")));
        if (P mdef = ptrF(it, "playerCombatMoveDefinition")) {
            extra = "," + kv("desc", loc(fieldAddr(mdef, "descriptionLocId")));
            auto mt = g_moveTypes.find(mdef);
            if (mt != g_moveTypes.end()) extra += ",\"t\":" + mt->second;
        }
        out += std::string(first ? "" : ",") + "{" + kv("label", label) + "," + kv("sub", sub) + "," + kvb("ok", ok) + extra + "}";
        first = false;
    }
    return out + "]}";
}

void doAction(const std::string& a) {
    P enc = ptrF(managerInstance("CombatManager"), "currentEncounter");
    P list = enc ? activeList(enc) : 0;
    if (!list) { LOGI("action %s: no active list", a.c_str()); return; }
    if (a == "back") { invoke(list, 0, "OnBack", 0, nullptr); return; }
    if (a.rfind("pick:", 0) == 0) {
        int i = atoi(a.c_str() + 5);
        auto items = listRefs(ptrF(list, "items"));
        if (i < 0 || i >= (int)items.size()) return;
        bool trans = false;
        void* na[2] = {&i, &trans};
        invoke(list, 0, "NavigateToItem", 2, na);
        // what a real press does: the item's ISubmitHandler.OnSubmit(BaseEventData)
        void* ea[1] = {nullptr};
        if (!invokeOk(items[i], "OnSubmit", 1, ea)) { void* sa[2] = {(void*)items[i], &i}; invoke(list, 0, "OnItemSubmitted", 2, sa); }
        LOGI("action pick %d on %s", i, className(list).c_str());
    }
}

// ---- Phase 0 probe (PLAN.md): can the engine draw on the bottom screen?
// Java hands Unity a Surface with UnityPlayer.displayChanged(1, surface) and then queues "probe:display".
// We log what Unity thinks its displays are and put a magenta camera on display 1.
P g_probeCam = 0;
void logDisplays() {
    void* dc = findClass("UnityEngine.Display");
    void* f = dc ? api.class_get_field_from_name(dc, "displays") : nullptr;
    P arr = 0;
    if (f) api.field_static_get_value(f, &arr);
    int n = arr ? rd<int>(arr + 0x18) : -1;
    LOGI("probe: Display.displays.Length = %d", n);
    for (int i = 0; i < n && i < 8; i++) {
        P d = rd<P>(arr + 0x20 + (P)i * 8);
        LOGI("probe: display %d rendering %dx%d system %dx%d", i, invokeInt(d, "get_renderingWidth", 0, nullptr),
             invokeInt(d, "get_renderingHeight", 0, nullptr), invokeInt(d, "get_systemWidth", 0, nullptr), invokeInt(d, "get_systemHeight", 0, nullptr));
    }
}
void probeDisplay() {
    logDisplays();
    if (g_probeCam) return;
    void* goK = findClass("UnityEngine.GameObject");
    void* camK = findClass("UnityEngine.Camera");
    void* objK = findClass("UnityEngine.Object");
    if (!goK || !camK || !objK) { LOGE("probe: Unity classes missing"); return; }
    P go = (P)api.object_new(goK);
    void* na[1] = {api.string_new("SosDuoProbeCamera")};
    if (!invokeOk(go, ".ctor", 1, na)) { LOGE("probe: GameObject ctor failed"); return; }
    void* da[1] = {(void*)go};
    invoke(0, (P)objK, "DontDestroyOnLoad", 1, da);
    void* ta[1] = {api.type_get_object(api.class_get_type(camK))};
    P cam = invoke(go, 0, "AddComponent", 1, ta);
    if (!cam) { LOGE("probe: AddComponent(Camera) failed"); return; }
    int disp = 1, flags = 2 /* SolidColor */, mask = 0; float depth = 100, col[4] = {0.016f, 0.024f, 0.055f, 1};
    void* a1[1] = {&disp};  bool ok = invokeOk(cam, "set_targetDisplay", 1, a1);
    void* a2[1] = {&flags}; ok = invokeOk(cam, "set_clearFlags", 1, a2) && ok;
    void* a3[1] = {col};    ok = invokeOk(cam, "set_backgroundColor", 1, a3) && ok;
    void* a4[1] = {&mask};  ok = invokeOk(cam, "set_cullingMask", 1, a4) && ok;
    void* a5[1] = {&depth}; ok = invokeOk(cam, "set_depth", 1, a5) && ok;
    g_probeCam = cam;
    LOGI("probe: magenta camera on display 1 created (setters %s), targetDisplay now %d", ok ? "ok" : "FAILED", invokeInt(cam, "get_targetDisplay", 0, nullptr));
}

// ---- debug / experiment commands (sent from adb through files/duo_cmd.txt, see HostActivity.poll)
P typeObj(void* k) { return (P)api.type_get_object(api.class_get_type(k)); }
std::vector<P> findAll(const char* cls) {
    std::vector<P> v;
    void* k = findClass(cls); void* objK = findClass("UnityEngine.Object");
    if (!k || !objK) return v;
    bool inactive = true;
    void* a[2] = {(void*)typeObj(k), &inactive};
    P arr = invoke(0, (P)objK, "FindObjectsOfType", 2, a);
    int n = arr ? rd<int>(arr + 0x18) : 0;
    for (int i = 0; i < n && i < 6000; i++) v.push_back(rd<P>(arr + 0x20 + (P)i * 8));
    return v;
}
void setActive(P comp, bool on) {
    P go = comp ? invoke(comp, 0, "get_gameObject", 0, nullptr) : 0;
    if (!go) return;
    void* a[1] = {&on};
    invokeOk(go, "SetActive", 1, a);
}
void hudVisible(bool on) {
    auto huds = findAll("InGameHud");
    static int logged = 0; if (logged++ < 3) LOGI("hud: %zu InGameHud, set %d", huds.size(), on);
    for (P h : huds)
        for (const char* n : {"playerStatuses", "playerBattleUI", "rightBarPanel", "zoneNamePanel", "xpPanel"}) setActive(ptrF(h, n), on);
}
float g_zoom = 0;   // >0: probe camera follows the main camera every frame with orthographicSize * g_zoom
P g_followedCam = 0;
void followMain() {
    static void* camK = findClass("UnityEngine.Camera");
    P mc = invoke(0, (P)camK, "get_main", 0, nullptr);
    if (!mc || !g_probeCam || mc == g_probeCam) return;
    P st = invoke(mc, 0, "get_transform", 0, nullptr), dt = invoke(g_probeCam, 0, "get_transform", 0, nullptr);
    if (!st || !dt) return;
    if (mc != g_followedCam) {     // new level / new camera: copy orientation and projection once
        g_followedCam = mc;
        P rot = invoke(st, 0, "get_rotation", 0, nullptr);
        if (rot) { void* qa[1] = {api.object_unbox((void*)rot)}; invokeOk(dt, "set_rotation", 1, qa); }
        for (const char* prop : {"orthographic", "nearClipPlane", "farClipPlane", "fieldOfView"}) {
            std::string g = std::string("get_") + prop, s2 = std::string("set_") + prop;
            P v = invoke(mc, 0, g.c_str(), 0, nullptr);
            if (v) { void* va[1] = {api.object_unbox((void*)v)}; invokeOk(g_probeCam, s2.c_str(), 1, va); }
        }
        int mask = -1; void* ma[1] = {&mask}; invokeOk(g_probeCam, "set_cullingMask", 1, ma);
    }
    extern float g_viewOx, g_viewOy;
    P v = invoke(mc, 0, "get_orthographicSize", 0, nullptr);
    float size = v ? *(float*)api.object_unbox((void*)v) * g_zoom : 0;
    if (v) { void* va[1] = {&size}; invokeOk(g_probeCam, "set_orthographicSize", 1, va); }
    P pos = invoke(st, 0, "get_position", 0, nullptr);
    if (pos) {
        float p[3]; memcpy(p, api.object_unbox((void*)pos), 12);
        // shift the camera so the main camera's centre lands at (g_viewOx, g_viewOy) px from the screen centre
        P r = invoke(st, 0, "get_right", 0, nullptr), u = invoke(st, 0, "get_up", 0, nullptr);
        if (r && u && size > 0) {
            float* rv = (float*)api.object_unbox((void*)r); float* uv = (float*)api.object_unbox((void*)u);
            float upp = 2 * size / 1080.f;                       // world units per pixel (display 1 is a 1920x1080 target)
            for (int k = 0; k < 3; k++) p[k] += -g_viewOx * upp * rv[k] + g_viewOy * upp * uv[k];
        }
        void* pa[1] = {p}; invokeOk(dt, "set_position", 1, pa);
    }
}
// Experiment: a second canvas on display 1 with the game's own HUD panels moved onto it.
void hudToBottom(float scale) {
    void* goK = findClass("UnityEngine.GameObject"); void* cvK = findClass("UnityEngine.Canvas");
    if (!goK || !cvK || !g_probeCam) return;
    static P canvas = 0;
    if (!canvas) {
        P go = (P)api.object_new(goK);
        void* na[1] = {api.string_new("SosDuoCanvas")};
        invokeOk(go, ".ctor", 1, na);
        int layer = 5; void* la[1] = {&layer}; invokeOk(go, "set_layer", 1, la);
        void* ta[1] = {(void*)typeObj(cvK)};
        canvas = invoke(go, 0, "AddComponent", 1, ta);
        if (!canvas) { LOGE("hud2: no canvas"); return; }
        int mode = 1; void* ma[1] = {&mode}; invokeOk(canvas, "set_renderMode", 1, ma);
        void* ca[1] = {(void*)g_probeCam}; invokeOk(canvas, "set_worldCamera", 1, ca);
        float dist = 10; void* da[1] = {&dist}; invokeOk(canvas, "set_planeDistance", 1, da);
        int mask = 1 << 5; void* ka[1] = {&mask}; invokeOk(g_probeCam, "set_cullingMask", 1, ka);
        g_zoom = 0;
    }
    void* sa[1] = {&scale}; invokeOk(canvas, "set_scaleFactor", 1, sa);
    P ct = invoke(canvas, 0, "get_transform", 0, nullptr);
    int moved = 0;
    for (P h : findAll("InGameHud"))
        for (const char* n : {"playerStatuses", "playerBattleUI", "rightBarPanel", "zoneNamePanel"}) {
            P pn = ptrF(h, n); if (!pn) continue;
            P tr = invoke(pn, 0, "get_transform", 0, nullptr);
            bool stay = false; void* pa[2] = {(void*)ct, &stay};
            if (tr && invokeOk(tr, "SetParent", 2, pa)) moved++;
        }
    LOGI("hud2: canvas %lx scale %.1f, moved %d panels", (unsigned long)canvas, scale, moved);
}
bool readTexture(P tex, Portrait& out);
void writeBin(const std::string& stem, const Portrait& p);
std::string objName(P o);
std::string safeName(std::string n);
extern std::string g_dir;
bool fileExists(const std::string& f);
bool invokeBool(P obj, const char* name, int argc, void** args, bool dflt);
// "probe:sprite": what the battle banner's inline button picture is drawn from (sprite asset, sheet, glyph rects).
void probeSprite() {
    auto b = findAll("BattleScreen");
    if (b.empty()) { LOGI("sprite: no BattleScreen"); return; }
    mkdir((g_dir + "/duodump").c_str(), 0775);
    void* cRT = findClass("UnityEngine.RenderTexture");
    P act = cRT ? call(methodSig(cRT, "get_active", {}), 0, nullptr) : 0;
    LOGI("sprite: RenderTexture.active = %lx %s", (unsigned long)act, act ? objName(act).c_str() : "-");
    auto tex = [&](const char* what, P t) {
        if (!t) { LOGI("sprite:   %s: null", what); return; }
        std::string nm = objName(t);
        LOGI("sprite:   %s: %lx %s '%s' %dx%d", what, (unsigned long)t, className(t).c_str(), nm.c_str(), invokeInt(t, "get_width", 0, nullptr, 0), invokeInt(t, "get_height", 0, nullptr, 0));
        Portrait img;
        if (readTexture(t, img)) writeBin(std::string("probe_") + what + "_" + safeName(nm), img);
    };
    auto asset = [&](const char* what, P sa) {
        if (!sa) { LOGI("sprite:  %s asset: null", what); return; }
        LOGI("sprite:  %s asset %lx '%s'", what, (unsigned long)sa, objName(sa).c_str());
        tex((std::string(what) + "_sheet").c_str(), ptrF(sa, "spriteSheet"));
        P mat = ptrF(sa, "material");
        tex((std::string(what) + "_mat").c_str(), mat ? invoke(mat, 0, "get_mainTexture", 0, nullptr) : 0);
        std::unordered_map<unsigned, P> glyphs;
        for (P g : listRefs(ptrF(sa, "m_SpriteGlyphTable"))) if (g) glyphs[valF<unsigned>(g, "m_Index")] = g;
        for (P c : listRefs(ptrF(sa, "m_SpriteCharacterTable"))) {
            if (!c) continue;
            P g = glyphs[valF<unsigned>(c, "m_GlyphIndex")];
            P r = g ? fieldAddr(g, "m_GlyphRect") : 0, m = g ? fieldAddr(g, "m_Metrics") : 0;
            P sp = g ? ptrF(g, "sprite") : 0;
            LOGI("sprite:    '%s' glyph %u rect %d %d %d %d metrics %.1f %.1f scale %.2f sprite '%s'", str(ptrF(c, "m_Name")).c_str(), valF<unsigned>(c, "m_GlyphIndex"),
                 r ? rd<int>(r) : -1, r ? rd<int>(r + 4) : -1, r ? rd<int>(r + 8) : -1, r ? rd<int>(r + 12) : -1, m ? rd<float>(m) : 0, m ? rd<float>(m + 4) : 0,
                 g ? valF<float>(g, "m_Scale", 1) : 0, sp ? objName(sp).c_str() : "-");
        }
    };
    // which controller the game thinks is in use (decides the button pictures)
    P im = managerInstance("InputManager");
    for (P c : {ptrF(im, "lastActiveController"), im ? invoke(im, 0, "GetLastUsedController", 0, nullptr) : (P)0}) {
        if (!c) { LOGI("sprite: controller null"); continue; }
        P g = invoke(c, 0, "get_hardwareTypeGuid", 0, nullptr);
        P ex = invoke(c, 0, "get_extension", 0, nullptr);
        LOGI("sprite: controller %s name '%s' hw '%s' id '%s' guid '%s' type %d ext %s buttons %d", className(c).c_str(), str(invoke(c, 0, "get_name", 0, nullptr)).c_str(),
             str(invoke(c, 0, "get_hardwareName", 0, nullptr)).c_str(), str(invoke(c, 0, "get_hardwareIdentifier", 0, nullptr)).c_str(),
             g ? str(invoke(g, 0, "ToString", 0, nullptr)).c_str() : "?", invokeInt(c, "get_type", 0, nullptr), ex ? className(ex).c_str() : "-", invokeInt(c, "get_buttonCount", 0, nullptr));
    }
    void* resK = findClass("UnityEngine.Resources"); void* saK = findClass("TMPro.TMP_SpriteAsset"); void* setK = findClass("TMPro.TMP_Settings");
    P defSa = setK ? invoke(0, (P)setK, "get_defaultSpriteAsset", 0, nullptr) : 0;
    std::string defPath = setK ? str(invoke(0, (P)setK, "get_defaultSpriteAssetPath", 0, nullptr)) : "";
    LOGI("sprite: TMP default sprite asset '%s' path '%s'", defSa ? objName(defSa).c_str() : "-", defPath.c_str());
    for (const char* nm : {"dialog-generic-inputs-template-mobile-controller", "dialog-generic-inputs-xbox", "dialog-generic-inputs-ps", "MissingButtons"}) {
        std::string path = defPath + nm;
        void* la[2] = {api.string_new(path.c_str()), (void*)typeObj(saK)};
        P sa = (resK && saK) ? invoke(0, (P)resK, "Load", 2, la) : 0;
        LOGI("sprite: Resources.Load(%s) = %lx", path.c_str(), (unsigned long)sa);
        if (sa) asset(nm[0] == 'M' ? "missing" : strstr(nm, "template") ? "template" : strstr(nm, "xbox") ? "xbox" : "ps", sa);
    }
    for (const char* field : {"combatInfoBox", "dialogBox"}) {
        P tmp = ptrF(ptrF(ptrF(b[0], field), "textLocalizer"), "textMeshProText");
        if (!tmp) continue;
        LOGI("sprite: %s text '%s'", field, str(ptrF(tmp, "m_text")).c_str());
        asset("text", ptrF(tmp, "m_spriteAsset"));
        asset("current", ptrF(tmp, "m_currentSpriteAsset"));
        P subs = ptrF(tmp, "m_subTextObjects");
        int n = subs ? rd<int>(subs + 0x18) : 0;
        for (int i = 0; i < n && i < 8; i++) {
            P s = rd<P>(subs + 0x20 + (P)i * 8);
            if (!s) continue;
            P go = invoke(s, 0, "get_gameObject", 0, nullptr);
            LOGI("sprite: sub %d '%s' active %d", i, objName(s).c_str(), go ? invokeBool(go, "get_activeInHierarchy", 0, nullptr, false) : -1);
            char nm[24]; snprintf(nm, sizeof nm, "sub%d", i);
            asset(nm, ptrF(s, "m_spriteAsset"));
            P mat = ptrF(s, "m_sharedMaterial");
            tex((std::string(nm) + "_shared").c_str(), mat ? invoke(mat, 0, "get_mainTexture", 0, nullptr) : 0);
            P fa = ptrF(s, "m_fontAsset");
            if (fa) LOGI("sprite:   font asset '%s'", objName(fa).c_str());
        }
    }
}
// ---- button pictures for the Thor's own pad. Rewired knows it as "Odin Controller" and calls its buttons
// "Button 1".."Button 4", "L1", "R2", "Select"..., and the game's prompt text asks its controller glyph sheet for a
// picture with that name. The sheet only knows "A", "B", "Left Shoulder"..., so the game drew its "missing button"
// picture instead ("Hold _ for power!", reported 2026-11-18). The pad's names are added to the sheet, each pointing
// at the glyph the sheet already has. Checked against the game's own bindings ("probe:pad"): Button 1 = confirm (A,
// confirmed by the user), 2 = cancel (B), 3 = bracelet / secondary (X), 4 = menu (Y).
void fixPadGlyphs(bool log) {
    static const char* alias[][2] = {
        {"Button 1", "A"}, {"Button 2", "B"}, {"Button 3", "X"}, {"Button 4", "Y"},
        {"L1", "Left Shoulder"}, {"R1", "Right Shoulder"}, {"L2", "Left Trigger"}, {"R2", "Right Trigger"}, {"Select", "Back"}};
    void* resK = findClass("UnityEngine.Resources"); void* saK = findClass("TMPro.TMP_SpriteAsset"); void* chK = findClass("TMPro.TMP_SpriteCharacter");
    if (!resK || !saK || !chK) return;
    void* la[2] = {api.string_new("Sprite Assets/dialog-generic-inputs-template-mobile-controller"), (void*)typeObj(saK)};
    P sa = invoke(0, (P)resK, "Load", 2, la);
    P table = ptrF(sa, "m_SpriteCharacterTable");
    if (!sa || !table) { if (log) LOGI("pad glyphs: sheet not loaded"); return; }
    std::unordered_map<std::string, P> have;
    for (P c : listRefs(table)) if (c) have[str(ptrF(c, "m_Name"))] = c;
    std::unordered_map<unsigned, P> glyphs;     // a character's own glyph reference is only filled in lazily
    for (P g : listRefs(ptrF(sa, "m_SpriteGlyphTable"))) if (g) glyphs[valF<unsigned>(g, "m_Index")] = g;
    // The Thor's buttons are labelled like an Xbox pad (A at the bottom), but for a pad it does not recognise the game
    // only has its generic "four dots" pictures. User, 2026-11-18: show the lettered buttons. Only the PICTURE of the
    // generic sheet is exchanged for the game's own Xbox one (same grid), plus the cell of each same-named glyph where
    // the two differ by a pixel or two. The sheet's tables stay its own: pointing them at the Xbox sheet's tables
    // froze the battle at its start (tried and reverted the same day).
    void* lx[2] = {api.string_new("Sprite Assets/dialog-generic-inputs-xbox"), (void*)typeObj(saK)};
    P xb = invoke(0, (P)resK, "Load", 2, lx);
    P xtex = ptrF(xb, "spriteSheet"), mat = ptrF(sa, "material");
    if (xtex && mat && ptrF(sa, "spriteSheet") != xtex && !fileExists(g_dir + "/duo_nolettered.txt")) {
        std::unordered_map<unsigned, P> xg;
        for (P g : listRefs(ptrF(xb, "m_SpriteGlyphTable"))) if (g) xg[valF<unsigned>(g, "m_Index")] = g;
        int cells = 0;
        for (P xc : listRefs(ptrF(xb, "m_SpriteCharacterTable"))) {
            auto own = xc ? have.find(str(ptrF(xc, "m_Name"))) : have.end();
            if (own == have.end()) continue;
            P from = xg[valF<unsigned>(xc, "m_GlyphIndex")], to = glyphs[valF<unsigned>(own->second, "m_GlyphIndex")];
            P fr = from ? fieldAddr(from, "m_GlyphRect") : 0, tr = to ? fieldAddr(to, "m_GlyphRect") : 0;
            P fm = from ? fieldAddr(from, "m_Metrics") : 0, tm = to ? fieldAddr(to, "m_Metrics") : 0;
            if (!fr || !tr || !fm || !tm) continue;
            memcpy((void*)tr, (void*)fr, 16); memcpy((void*)tm, (void*)fm, 20); cells++;
        }
        void* ta[1] = {(void*)xtex};
        if (invokeOk(mat, "set_mainTexture", 1, ta)) { *(P*)fieldAddr(sa, "spriteSheet") = xtex; LOGI("pad glyphs: lettered pictures on the generic sheet (%d cells)", cells); }
    }
    int added = 0;
    for (auto& al : alias) {
        if (have.count(al[0])) continue;
        auto src = have.find(al[1]);
        P glyph = src == have.end() ? 0 : glyphs[valF<unsigned>(src->second, "m_GlyphIndex")];
        if (!glyph) { if (log) LOGI("pad glyphs: no '%s' on the sheet", al[1]); continue; }
        P ch = (P)api.object_new(chK);
        unsigned uni = 0xFFFE;
        void* ca[3] = {&uni, (void*)sa, (void*)glyph};
        call(methodSig(chK, ".ctor", {"System.UInt32", "TMPro.TMP_SpriteAsset", "TMPro.TMP_SpriteGlyph"}), ch, ca);
        void* na[1] = {api.string_new(al[0])};
        if (!invokeOk(ch, "set_name", 1, na)) continue;
        float sc = valF<float>(src->second, "m_Scale", 1.f); void* sa1[1] = {&sc};
        invokeOk(ch, "set_scale", 1, sa1);
        void* aa[1] = {(void*)ch};
        if (invokeOk(table, "Add", 1, aa)) added++;
    }
    if (added) { invokeOk(sa, "UpdateLookupTables", 0, nullptr); LOGI("pad glyphs: %d names added to the controller sheet", added); }
    else if (log) LOGI("pad glyphs: nothing to add (%zu names on the sheet)", have.size());
    if (log) for (auto& al : alias) { void* na[1] = {api.string_new(al[0])}; LOGI("pad glyphs: '%s' -> sprite index %d", al[0], invokeInt(sa, "GetSpriteIndexFromName", 1, na, -2)); }
}
// "probe:pad": the pad's element names and what the game has bound to them (to check the table above).
void probePad() {
    P im = managerInstance("InputManager");
    P c = im ? invoke(im, 0, "GetLastUsedController", 0, nullptr) : 0;
    P pl = im ? invoke(im, 0, "get_FirstPlayer", 0, nullptr) : 0;
    P chlp = pl ? invoke(pl, 0, "get_controllers", 0, nullptr) : 0;
    P joys = chlp ? invoke(chlp, 0, "get_Joysticks", 0, nullptr) : 0;
    if (joys && invokeInt(joys, "get_Count", 0, nullptr, 0) > 0) { int z = 0; void* ja[1] = {&z}; P j = invoke(joys, 0, "get_Item", 1, ja); if (j) c = j; }
    if (!c || !pl) { LOGI("pad: no controller (%d) or player (%d)", c != 0, pl != 0); return; }
    LOGI("pad: %s '%s' id '%s'", className(c).c_str(), str(invoke(c, 0, "get_name", 0, nullptr)).c_str(), str(invoke(c, 0, "get_hardwareIdentifier", 0, nullptr)).c_str());
    P ids = invoke(c, 0, "get_ElementIdentifiers", 0, nullptr);
    int n = ids ? invokeInt(ids, "get_Count", 0, nullptr, 0) : 0;
    std::string names;
    for (int i = 0; i < n && i < 64; i++) { void* ia[1] = {&i}; P e = invoke(ids, 0, "get_Item", 1, ia); if (e) names += "[" + std::to_string(invokeInt(e, "get_id", 0, nullptr)) + " " + str(invoke(e, 0, "get_name", 0, nullptr)) + "] "; }
    LOGI("pad: %d elements: %s", n, names.c_str());
    void* reK = findClass("Rewired.ReInput");
    P mapping = reK ? invoke(0, (P)reK, "get_mapping", 0, nullptr) : 0;
    P acts = mapping ? invoke(mapping, 0, "get_Actions", 0, nullptr) : 0;
    P maps = ptrF(ptrF(pl, "controllers"), "maps");
    if (!maps) { P ch = invoke(pl, 0, "get_controllers", 0, nullptr); maps = ch ? invoke(ch, 0, "get_maps", 0, nullptr) : 0; }
    int an = acts ? invokeInt(acts, "get_Count", 0, nullptr, 0) : 0;
    LOGI("pad: %d actions, maps %lx", an, (unsigned long)maps);
    void* m = maps ? methodSig((void*)klassOf(maps), "GetFirstElementMapWithAction", {"Rewired.Controller", "System.String", "System.Boolean"}) : nullptr;
    for (int i = 0; m && i < an && i < 200; i++) {
        void* ia[1] = {&i}; P a = invoke(acts, 0, "get_Item", 1, ia);
        P nm = a ? invoke(a, 0, "get_name", 0, nullptr) : 0;
        if (!nm) continue;
        bool skip = false; void* ga[3] = {(void*)c, (void*)nm, &skip};
        P aem = call(m, maps, ga);
        if (aem) LOGI("pad:   %s -> '%s'", str(nm).c_str(), str(invoke(aem, 0, "get_elementIdentifierName", 0, nullptr)).c_str());
    }
}
void probeCmd(const std::string& a) {
    float f[4] = {0, 0, 1, 1}; int w = 0, h = 0;
    if (a == "probe:pad") { probePad(); fixPadGlyphs(true); return; }
    if (a == "probe:sprite") { probeSprite(); return; }
    if (sscanf(a.c_str(), "probe:rect %f %f %f %f", &f[0], &f[1], &f[2], &f[3]) == 4 && g_probeCam) {
        void* ra[1] = {f};
        LOGI("probe: set_rect %s", invokeOk(g_probeCam, "set_rect", 1, ra) ? "ok" : "failed");
    } else if (sscanf(a.c_str(), "probe:color %f %f %f", &f[0], &f[1], &f[2]) == 3 && g_probeCam) {
        f[3] = 1; void* ca[1] = {f};
        LOGI("probe: set_backgroundColor %s", invokeOk(g_probeCam, "set_backgroundColor", 1, ca) ? "ok" : "failed");
    } else if (a == "probe:world" && g_probeCam) {
        // show the game world on display 1: copy the main camera's pose and projection
        void* camK = findClass("UnityEngine.Camera");
        P mc = invoke(0, (P)camK, "get_main", 0, nullptr);
        if (!mc) { LOGE("probe: no main camera"); return; }
        P st = invoke(mc, 0, "get_transform", 0, nullptr), dt = invoke(g_probeCam, 0, "get_transform", 0, nullptr);
        P pos = invoke(st, 0, "get_position", 0, nullptr), rot = invoke(st, 0, "get_rotation", 0, nullptr);
        if (pos) { void* pa[1] = {api.object_unbox((void*)pos)}; invokeOk(dt, "set_position", 1, pa); }
        if (rot) { void* qa[1] = {api.object_unbox((void*)rot)}; invokeOk(dt, "set_rotation", 1, qa); }
        for (const char* prop : {"orthographic", "orthographicSize", "nearClipPlane", "farClipPlane", "fieldOfView"}) {
            std::string g = std::string("get_") + prop, st2 = std::string("set_") + prop;
            P v = invoke(mc, 0, g.c_str(), 0, nullptr);
            if (v) { void* va[1] = {api.object_unbox((void*)v)}; invokeOk(g_probeCam, st2.c_str(), 1, va); }
        }
        int mask = -1; void* ma[1] = {&mask}; invokeOk(g_probeCam, "set_cullingMask", 1, ma);
        float* pp = pos ? (float*)api.object_unbox((void*)pos) : nullptr;
        LOGI("probe: world camera copied from main (pos %.1f %.1f %.1f) main pixel %dx%d", pp ? pp[0] : 0, pp ? pp[1] : 0, pp ? pp[2] : 0,
             invokeInt(mc, "get_pixelWidth", 0, nullptr), invokeInt(mc, "get_pixelHeight", 0, nullptr));
    } else if (sscanf(a.c_str(), "probe:zoom %f", &f[0]) == 1) {
        g_zoom = f[0]; LOGI("probe: zoom %.2f", g_zoom);
    } else if (sscanf(a.c_str(), "probe:hud2 %f", &f[0]) == 1) {
        hudToBottom(f[0]);
    } else if (a == "probe:info" && g_probeCam) {
        LOGI("probe: cam pixel %dx%d", invokeInt(g_probeCam, "get_pixelWidth", 0, nullptr), invokeInt(g_probeCam, "get_pixelHeight", 0, nullptr));
        logDisplays();
    } else if (sscanf(a.c_str(), "probe:activate %d %d", &w, &h) == 2) {
        typedef void (*Act)(P, int, int, uint64_t);
        Act fn = api.resolve_icall ? (Act)api.resolve_icall("UnityEngine.Display::ActivateDisplayImpl") : nullptr;
        void* dc = findClass("UnityEngine.Display");
        void* fld = dc ? api.class_get_field_from_name(dc, "displays") : nullptr;
        P arr = 0; if (fld) api.field_static_get_value(fld, &arr);
        P d1 = (arr && rd<int>(arr + 0x18) > 1) ? rd<P>(arr + 0x28) : 0;
        LOGI("probe: ActivateDisplayImpl icall %p display1 %lx", fn, (unsigned long)d1);
        if (fn && d1) { fn(rd<P>(d1 + 0x10), w, h, ((uint64_t)1 << 32) | 60); logDisplays(); }
    } else if (a == "probe:cam off" || a == "probe:cam on") {
        bool on = a == "probe:cam on"; void* ea[1] = {&on};
        if (g_probeCam) invokeOk(g_probeCam, "set_enabled", 1, ea);
    }
}

// Whole texture -> RGBA (rows bottom-up). Used for font atlases.
bool readTexture(P tex, Portrait& out) {
    void* cRT = findClass("UnityEngine.RenderTexture"); void* cT2D = findClass("UnityEngine.Texture2D");
    void* cGfx = findClass("UnityEngine.Graphics"); void* cObj = findClass("UnityEngine.Object");
    int tw = invokeInt(tex, "get_width", 0, nullptr, 0), th = invokeInt(tex, "get_height", 0, nullptr, 0);
    if (!cRT || !cT2D || !cGfx || !cObj || tw <= 0 || th <= 0 || tw > 4096 || th > 4096) return false;
    P rt = (P)api.object_new(cRT);
    int depth = 0, gfmt = 8;   // R8G8B8A8_UNorm: no colour conversion
    void* a1[4] = {&tw, &th, &depth, &gfmt};
    call(methodSig(cRT, ".ctor", {"System.Int32", "System.Int32", "System.Int32", "UnityEngine.Experimental.Rendering.GraphicsFormat"}), rt, a1);
    void* a2[2] = {(void*)tex, (void*)rt};
    call(methodSig(cGfx, "Blit", {"UnityEngine.Texture", "UnityEngine.RenderTexture"}), 0, a2);
    P prev = call(methodSig(cRT, "get_active", {}), 0, nullptr);
    void* a3[1] = {(void*)rt};
    call(methodSig(cRT, "set_active", {"UnityEngine.RenderTexture"}), 0, a3);
    P t2 = (P)api.object_new(cT2D);
    int fmt = 4; bool mip = false, lin = true;
    void* a4[5] = {&tw, &th, &fmt, &mip, &lin};
    call(methodSig(cT2D, ".ctor", {"System.Int32", "System.Int32", "UnityEngine.TextureFormat", "System.Boolean", "System.Boolean"}), t2, a4);
    float rect[4] = {0, 0, (float)tw, (float)th}; int zero = 0;
    void* a5[3] = {rect, &zero, &zero};
    call(methodSig(cT2D, "ReadPixels", {"UnityEngine.Rect", "System.Int32", "System.Int32"}), t2, a5);
    P px = call(methodSig(cT2D, "GetPixels32", {}), t2, nullptr);
    void* a6[1] = {(void*)prev};
    call(methodSig(cRT, "set_active", {"UnityEngine.RenderTexture"}), 0, a6);
    void* d1[1] = {(void*)rt}; call(methodSig(cObj, "Destroy", {"UnityEngine.Object"}), 0, d1);
    void* d2[1] = {(void*)t2}; call(methodSig(cObj, "Destroy", {"UnityEngine.Object"}), 0, d2);
    if (!px) return false;
    size_t n = (size_t)tw * th * 4;
    if ((size_t)rd<int64_t>(px + 0x18) * 4 < n) return false;
    out.w = tw; out.h = th; out.rgba.resize(n);
    return safeRead(px + 0x20, out.rgba.data(), n);
}

// ---- asset dump (debug): writes the game's loaded UI sprites and TMP fonts as raw files so the
// bottom-screen UI can be designed from the real art. Output: <files>/duodump/. Game assets never go in the repo.
std::string g_dir;
std::vector<std::pair<std::string, P>> g_dumpQ;   // (file stem, Sprite)
FILE* g_dumpIdx = nullptr;
std::string safeName(std::string n) { for (auto& c : n) if (!isalnum((unsigned char)c) && c != '_' && c != '-') c = '_'; return n.substr(0, 60); }
std::string objName(P o) { return o ? str(invoke(o, 0, "get_name", 0, nullptr)) : ""; }
void writeBin(const std::string& stem, const Portrait& p) {
    FILE* f = fopen((g_dir + "/duodump/" + stem + ".bin").c_str(), "wb");
    if (!f) return;
    fwrite(&p.w, 4, 1, f); fwrite(&p.h, 4, 1, f); fwrite(p.rgba.data(), 1, p.rgba.size(), f); fclose(f);
}
void dumpFonts() {
    std::unordered_map<P, bool> seen;
    for (P t : findAll("TMPro.TextMeshProUGUI")) {
        P fa = ptrF(t, "m_fontAsset");
        if (!fa || seen[fa]) continue;
        seen[fa] = true;
        std::string nm = safeName(objName(fa));
        P atl = ptrF(fa, "m_AtlasTextures");
        P tex = (atl && rd<int>(atl + 0x18) > 0) ? rd<P>(atl + 0x20) : 0;
        Portrait img; bool ok = tex && readTexture(tex, img);
        if (ok) writeBin("font_" + nm, img);
        FILE* f = fopen((g_dir + "/duodump/font_" + nm + ".tsv").c_str(), "w");
        if (!f) continue;
        P fi = fieldAddr(fa, "m_FaceInfo");
        void* fiK = findClass("UnityEngine.TextCore.FaceInfo");
        auto fif = [&](const char* n) { int o = fiK ? fieldOff((P)fiK, n) - 0x10 : -1; return (fi && o >= 0) ? rd<float>(fi + o) : 0.f; };
        int psO = fiK ? fieldOff((P)fiK, "m_PointSize") - 0x10 : -1;
        fprintf(f, "#face\t%d\t%.2f\t%.2f\t%.2f\t%.2f\tatlas %dx%d mode %d pad %d\n", (fi && psO >= 0) ? rd<int>(fi + psO) : 0, fif("m_LineHeight"), fif("m_AscentLine"),
                fif("m_DescentLine"), fif("m_Baseline"), valF<int>(fa, "m_AtlasWidth"), valF<int>(fa, "m_AtlasHeight"), valF<int>(fa, "m_AtlasRenderMode"), valF<int>(fa, "m_AtlasPadding"));
        std::unordered_map<unsigned, P> glyphs;
        for (P g : listRefs(ptrF(fa, "m_GlyphTable"))) glyphs[valF<unsigned>(g, "m_Index")] = g;
        int n = 0;
        for (P c : listRefs(ptrF(fa, "m_CharacterTable"))) {
            P g = glyphs[valF<unsigned>(c, "m_GlyphIndex")];
            if (!g) continue;
            P m = fieldAddr(g, "m_Metrics"), r = fieldAddr(g, "m_GlyphRect");
            // unicode, rect x y w h, metrics width height bearingX bearingY advance, scale
            fprintf(f, "%u\t%d\t%d\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\n", valF<unsigned>(c, "m_Unicode"), rd<int>(r), rd<int>(r + 4), rd<int>(r + 8), rd<int>(r + 12),
                    rd<float>(m), rd<float>(m + 4), rd<float>(m + 8), rd<float>(m + 12), rd<float>(m + 16), valF<float>(g, "m_Scale", 1));
            n++;
        }
        fclose(f);
        LOGI("dump: font %s atlas %s %dx%d, %d chars", nm.c_str(), ok ? "ok" : "FAILED", img.w, img.h, n);
    }
}
void dumpStart() {
    if (g_dir.empty()) { LOGE("dump: no dir"); return; }
    mkdir((g_dir + "/duodump").c_str(), 0775);
    if (g_dumpIdx) fclose(g_dumpIdx);
    g_dumpIdx = fopen((g_dir + "/duodump/index.tsv").c_str(), "w");
    g_dumpQ.clear();
    dumpFonts();
    std::unordered_map<P, bool> seen;
    for (P im : findAll("UnityEngine.UI.Image")) {
        P sp = invoke(im, 0, "get_sprite", 0, nullptr);
        if (!sp || seen[sp]) continue;
        seen[sp] = true;
        char stem[96]; snprintf(stem, sizeof stem, "s%03zu_%s", g_dumpQ.size(), safeName(objName(sp)).c_str());
        g_dumpQ.push_back({stem, sp});
    }
    std::reverse(g_dumpQ.begin(), g_dumpQ.end());
    LOGI("dump: %zu sprites queued", g_dumpQ.size());
}
void dumpStep() {   // a few sprites per frame so the game does not freeze
    for (int i = 0; i < 3 && !g_dumpQ.empty(); i++) {
        auto job = g_dumpQ.back(); g_dumpQ.pop_back();
        Portrait p;
        if (!readSprite(job.second, p)) continue;
        writeBin(job.first, p);
        float b[4] = {0, 0, 0, 0};
        P bb = invoke(job.second, 0, "get_border", 0, nullptr);
        if (bb) memcpy(b, api.object_unbox((void*)bb), sizeof b);
        if (g_dumpIdx) fprintf(g_dumpIdx, "%s\t%d\t%d\t%.0f\t%.0f\t%.0f\t%.0f\n", job.first.c_str(), p.w, p.h, b[0], b[1], b[2], b[3]);
    }
    if (g_dumpQ.empty() && g_dumpIdx) { fclose(g_dumpIdx); g_dumpIdx = nullptr; LOGI("dump: done"); }
}

// ---- runtime asset cache: the bottom screen is drawn with the game's own fonts and sprites.
// They are read from the running game into <files>/duocache/ (never shipped in the APK or the repo).
// File: 6 int32 (w, h, border left, bottom, right, top) + RGBA rows bottom-up.
std::vector<std::pair<std::string, P>> g_cacheQ;
std::unordered_map<std::string, bool> g_cached;
bool g_fontsCached = false;
bool fileExists(const std::string& f) { return access(f.c_str(), F_OK) == 0; }
void cacheScan() {
    if (g_dir.empty()) return;
    std::string dir = g_dir + "/duocache";
    mkdir(dir.c_str(), 0775);
    if (!g_fontsCached) {
        std::unordered_map<P, bool> seen;
        for (P t : findAll("TMPro.TextMeshProUGUI")) {
            P fa = ptrF(t, "m_fontAsset");
            if (!fa || seen[fa]) continue;
            seen[fa] = true;
            std::string nm = "font_" + safeName(objName(fa));
            if (fileExists(dir + "/" + nm + ".tsv")) continue;
            P atl = ptrF(fa, "m_AtlasTextures");
            P tex = (atl && rd<int>(atl + 0x18) > 0) ? rd<P>(atl + 0x20) : 0;
            Portrait img;
            if (!tex || !readTexture(tex, img)) continue;
            FILE* b = fopen((dir + "/" + nm + ".bin").c_str(), "wb");
            if (!b) continue;
            int hdr[6] = {img.w, img.h, 0, 0, 0, 0};
            fwrite(hdr, 4, 6, b); fwrite(img.rgba.data(), 1, img.rgba.size(), b); fclose(b);
            FILE* f = fopen((dir + "/" + nm + ".tmp").c_str(), "w");
            if (!f) continue;
            std::unordered_map<unsigned, P> glyphs;
            for (P g : listRefs(ptrF(fa, "m_GlyphTable"))) glyphs[valF<unsigned>(g, "m_Index")] = g;
            for (P c : listRefs(ptrF(fa, "m_CharacterTable"))) {
                P g = glyphs[valF<unsigned>(c, "m_GlyphIndex")];
                if (!g) continue;
                P m = fieldAddr(g, "m_Metrics"), r = fieldAddr(g, "m_GlyphRect");
                fprintf(f, "%u\t%d\t%d\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\n", valF<unsigned>(c, "m_Unicode"), rd<int>(r), rd<int>(r + 4), rd<int>(r + 8), rd<int>(r + 12),
                        rd<float>(m), rd<float>(m + 4), rd<float>(m + 8), rd<float>(m + 12), rd<float>(m + 16));
            }
            fclose(f);
            rename((dir + "/" + nm + ".tmp").c_str(), (dir + "/" + nm + ".tsv").c_str());
            g_fontsCached = true;
        }
    }
    int queued = 0;
    for (P im : findAll("UnityEngine.UI.Image")) {
        P sp = invoke(im, 0, "get_sprite", 0, nullptr);
        if (!sp) continue;
        std::string nm = safeName(objName(sp));
        if (nm.empty() || g_cached[nm]) continue;
        g_cached[nm] = true;
        if (fileExists(dir + "/" + nm + ".bin")) continue;
        g_cacheQ.push_back({nm, sp});
        queued++;
    }
    LOGI("cache: %d new sprites queued", queued);
}
void cacheStep() {
    for (int i = 0; i < 4 && !g_cacheQ.empty(); i++) {
        auto job = g_cacheQ.back(); g_cacheQ.pop_back();
        Portrait p;
        if (!readSprite(job.second, p)) continue;
        float b[4] = {0, 0, 0, 0};
        P bb = invoke(job.second, 0, "get_border", 0, nullptr);
        if (bb) memcpy(b, api.object_unbox((void*)bb), sizeof b);
        std::string base = g_dir + "/duocache/" + job.first;
        FILE* f = fopen((base + ".tmp").c_str(), "wb");
        if (!f) continue;
        int hdr[6] = {p.w, p.h, (int)b[0], (int)b[1], (int)b[2], (int)b[3]};
        fwrite(hdr, 4, 6, f); fwrite(p.rgba.data(), 1, p.rgba.size(), f); fclose(f);
        rename((base + ".tmp").c_str(), (base + ".bin").c_str());
    }
}

// ---- area view: a second world camera on display 1 that follows the game's camera, zoomed out.
float g_viewOx = 0, g_viewOy = 0;   // where the party should appear, in bottom-screen pixels from the centre
void areaView(float zoom) {
    if (!g_probeCam) probeDisplay();
    if (!g_probeCam) return;
    bool on = zoom > 0; void* ea[1] = {&on};
    invokeOk(g_probeCam, "set_enabled", 1, ea);
    float col[4] = {0.016f, 0.024f, 0.055f, 1}; void* ca[1] = {col};
    invokeOk(g_probeCam, "set_backgroundColor", 1, ca);
    g_followedCam = 0;
    g_zoom = zoom;
    LOGI("area view zoom %.2f", zoom);
}

// Party leader's world position, sampled on the main thread (Transform access is main-thread only).
float g_leaderPos[3]; bool g_leaderOk = false;
void sampleLeader() {
    P leader = ptrF(managerInstance("PlayerPartyManager"), "leader");
    P tr = leader ? invoke(leader, 0, "get_transform", 0, nullptr) : 0;
    P pos = tr ? invoke(tr, 0, "get_position", 0, nullptr) : 0;
    if (!pos) { g_leaderOk = false; return; }
    memcpy(g_leaderPos, api.object_unbox((void*)pos), sizeof g_leaderPos);
    g_leaderOk = true;
}

void toolsState();
bool invokeBool(P obj, const char* name, int argc, void** args, bool dflt);

// ---- top-screen HUD hiding: the widgets stay active and keep updating (the bottom screen reads them, and the
// battle flow waits on them), they are only made transparent with a CanvasGroup. g_fade < 0 = leave the HUD alone.
float g_fade = -1;
std::vector<P> g_keptGroups;      // back buttons currently told to ignore their (faded) panel
P g_keptFor = 0;                  // the list they belong to
std::vector<P> g_fadedGroups;     // CanvasGroups currently made transparent by fadeObj
void fadeObj(P comp, float alpha) {
    static void* goK = findClass("UnityEngine.GameObject");
    static void* cgK = findClass("UnityEngine.CanvasGroup");
    P go = comp ? invoke(comp, 0, "get_gameObject", 0, nullptr) : 0;
    if (!go || !goK || !cgK) return;
    void* ta[1] = {(void*)typeObj(cgK)};
    P cg = call(methodSig(goK, "GetComponent", {"System.Type"}), go, ta);
    if (!cg) cg = invoke(go, 0, "AddComponent", 1, ta);
    if (!cg) return;
    void* aa[1] = {&alpha};
    invokeOk(cg, "set_alpha", 1, aa);
    if (alpha < 1) { bool have = false; for (P q : g_fadedGroups) have |= q == cg; if (!have && g_fadedGroups.size() < 64) g_fadedGroups.push_back(cg); }
}
// Children of a faded frame that must stay visible (the list's back button): their own CanvasGroup ignores the parent's.
void keepVisible(P tr) {
    static void* goK = findClass("UnityEngine.GameObject");
    static void* cgK = findClass("UnityEngine.CanvasGroup");
    P go = tr ? invoke(tr, 0, "get_gameObject", 0, nullptr) : 0;
    if (!go || !goK || !cgK) return;
    void* ta[1] = {(void*)typeObj(cgK)};
    P cg = call(methodSig(goK, "GetComponent", {"System.Type"}), go, ta);
    if (!cg) cg = invoke(go, 0, "AddComponent", 1, ta);
    if (!cg) return;
    bool yes = true; float one = 1; void* ia[1] = {&yes}; void* aa[1] = {&one};
    invokeOk(cg, "set_ignoreParentGroups", 1, ia);
    invokeOk(cg, "set_alpha", 1, aa);
    bool have = false; for (P q : g_keptGroups) have |= q == cg;
    if (!have && g_keptGroups.size() < 32) g_keptGroups.push_back(cg);
}
// A kept button must follow its panel again as soon as that list is no longer the open one: the game hides a closing
// list through the panel's own transparency, and a button that still ignored it stayed on screen on its own
// (the "stuck button at the top right", reported 2026-11-18).
void releaseKept() {
    for (P cg : g_keptGroups) { bool no = false; void* ia[1] = {&no}; invoke(cg, 0, "set_ignoreParentGroups", 1, ia); }
    g_keptGroups.clear();
}
void keepBackButtons(P root, int depth = 0) {
    static P logged = 0;
    int n = invokeInt(root, "get_childCount", 0, nullptr, 0);
    for (int i = 0; i < n && i < 24; i++) {
        void* ia[1] = {&i};
        P ch = invoke(root, 0, "GetChild", 1, ia);
        if (!ch) continue;
        std::string nm = objName(ch), low = nm;
        for (auto& c : low) c = (char)tolower((unsigned char)c);
        if (depth == 0 && logged != root) LOGI("list frame child: %s", nm.c_str());
        if (low.find("back") != std::string::npos && low.find("background") == std::string::npos) { keepVisible(ch); continue; }
        if (depth < 3) keepBackButtons(ch, depth + 1);
    }
    if (depth == 0) logged = root;
}
void unfadeAll() {
    for (P cg : g_fadedGroups) { float one = 1; void* aa[1] = {&one}; invoke(cg, 0, "set_alpha", 1, aa); }
    g_fadedGroups.clear();
    releaseKept(); g_keptFor = 0;
}
void releaseKept();
void fadeHud(P hud, P bscr, P list) {
    if (!g_keptGroups.empty() && (g_fade < 0 || list != g_keptFor)) { releaseKept(); g_keptFor = 0; }
    if (g_fade < 0) return;
    // Rule (user, 2026-11-17): hide = transparent, and only what the bottom screen itself shows. So: the party panels and
    // the combo / ultimate badge, but NOT the character switcher that sits in the same group (the bottom has no such
    // control); the info bar, the battle banner, an open list and its frame, but NOT the list's back button.
    if (hud) {
        fadeObj(ptrF(hud, "playerStatuses"), g_fade);
        P pb = ptrF(hud, "playerBattleUI");
        for (P pn : listRefs(ptrF(pb, "playerPanelsList"))) if (pn) fadeObj(pn, g_fade);
        fadeObj(ptrF(pb, "comboPointsPanel"), g_fade);
    }
    if (bscr) { fadeObj(ptrF(bscr, "combatInfoBox"), g_fade); fadeObj(ptrF(bscr, "dialogBox"), g_fade); }
    if (list) {
        fadeObj(list, g_fade);
        // Lists sit in a framed panel, e.g. the touch one: SpecialMoveSelectorTouch < Content < Mask < BackgroundContent <
        // BackgroundVisual < GenericBackground < Container. Fading only the list left an empty box on the top screen, so the
        // whole frame goes with it: the outermost of the run of "...Background..." ancestors, whatever the layout (the
        // controller's list is a different object from the touch one). Its back button is kept visible.
        P tr = invoke(list, 0, "get_transform", 0, nullptr), frame = 0;
        std::string chain;
        for (int i = 0; tr && i < 8; i++) {
            std::string nm = objName(tr);
            chain += nm + " < ";
            if (nm.find("Background") != std::string::npos) frame = tr; else if (frame) break;
            tr = invoke(tr, 0, "get_parent", 0, nullptr);
        }
        static void* loggedK = nullptr;
        if (loggedK != (void*)klassOf(list)) { loggedK = (void*)klassOf(list); LOGI("list chain (%s): %s frame %s", className(list).c_str(), chain.c_str(), frame ? objName(frame).c_str() : "-"); }
        if (frame) { fadeObj(frame, g_fade); keepBackButtons(frame); g_keptFor = list; }
        // the hand cursor: transparent only while it points at something inside that hidden list (the bottom screen
        // draws the same hand there); pointing at a target in the scene it stays
        P within = frame ? frame : invoke(list, 0, "get_transform", 0, nullptr);
        for (auto& e : dictEntries(ptrF(managerInstance("UICursorManager"), "activeCursorStacks"))) {
            for (P cur : listRefs(rd<P>(e.val))) {
                if (!cur) continue;
                P tgt = ptrF(cur, "target");
                P ttr = tgt ? invoke(tgt, 0, "get_transform", 0, nullptr) : 0;
                void* ia[1] = {(void*)within};
                bool inside = ttr && within && invokeBool(ttr, "IsChildOf", 1, ia, false);
                fadeObj(cur, inside ? g_fade : 1.f);
            }
        }
    }
}

// Portrait sprite per character, read from the game's HUD widgets so the bottom screen shows the same face
// (kid / adult / costume variants) as the top screen would.
std::string safeName(std::string n);
std::string objName(P o);
void hudFaces() {
    auto huds = findAll("InGameHud");
    if (huds.empty()) return;
    std::unordered_map<std::string, std::string> m;
    auto take = [&](P widget, P idAddr) {
        std::string id = idAddr ? str(rd<P>(idAddr)) : "";
        P img = ptrF(ptrF(widget, "portrait"), "portrait");
        P sp = img ? invoke(img, 0, "get_sprite", 0, nullptr) : 0;
        if (!id.empty() && sp) m[id] = safeName(objName(sp));
    };
    P hud = huds[0];
    { P e = ptrF(managerInstance("CombatManager"), "currentEncounter"); fadeHud(hud, 0, e ? activeList(e) : 0); }   // outside battle: no list, so a kept button is released
    toolsState();
    for (P st : listRefs(ptrF(ptrF(hud, "playerStatuses"), "playerStatusList"))) if (st) take(st, fieldAddr(st, "characterDefinitionId"));
    for (P pn : listRefs(ptrF(ptrF(hud, "playerBattleUI"), "playerPanelsList"))) if (pn) take(pn, fieldAddr(pn, "characterDefinitionId"));
    std::string out = "{"; bool first = true;
    for (auto& kvp : m) { out += std::string(first ? "" : ",") + q(kvp.first) + ":" + q(kvp.second); first = false; }
    g_faces = out + "}";
}

// ---- quick commands: the bottom screen's buttons press the game's OWN touch buttons (menu, world map, camp...), so
// whatever opens is the game's real screen and nothing is changed behind its back.
P touchUI() { auto v = findAll("InGameTouchUI"); return v.empty() ? 0 : v[0]; }
// The game's touch buttons are Rewired TouchButtons (no UnityEngine.UI.Button, no onClick), so instead of clicking them
// we run the same steps the game's own input handler runs (InGameHud.ProcessInputs, read from the binary 2026-11-17):
//   menu: CanOpenAnyPanel && UIManager.IsGameMenuEnabled -> SinglePlayerPlusManager.SetUIInputsPlayer(player)
//         -> UIManager.InstantiatePreloadedScreen<GameMenu>(layer 3, null, transitionIn true, AnimatorUpdateMode 2)
//         -> Animator.Update(0) on the view's and the home section's animator
//   map:  CanOpenAnyPanel -> StreamingWorldMapManager.TryOpenWorldMapScreen(player)   (checks IsMapUnlocked itself)
// All the game's own "may this open now" checks are kept, so nothing opens that a controller press would not open.
P firstPlayer() { P im = managerInstance("InputManager"); return im ? invoke(im, 0, "get_FirstPlayer", 0, nullptr) : 0; }
bool canOpenPanel() {
    auto h = findAll("InGameHud");
    return !h.empty() && invokeBool(h[0], "CanOpenAnyPanel", 0, nullptr, false);
}
void animKick(P owner) {        // owner+0x20 = Animator on View and on AbstractGameMenuSection
    P an = owner ? rd<P>(owner + 0x20) : 0;
    if (!an || className(an) != "Animator") return;
    float z = 0; void* a[1] = {&z};
    invokeOk(an, "Update", 1, a);
}
P readyGameMenu() { for (P gm : findAll("GameMenu")) if (gm && valF<bool>(gm, "isReady")) return gm; return 0; }
bool openGameMenu() {
    if (readyGameMenu()) return true;                       // already open: only the section needs changing
    P ui = managerInstance("UIManager"), spm = managerInstance("SinglePlayerPlusManager"), pl = firstPlayer();
    void* gmK = findClass("GameMenu");
    if (!ui || !spm || !pl || !gmK) { LOGI("menu: not available (ui %d spm %d player %d)", ui != 0, spm != 0, pl != 0); return false; }
    if (!canOpenPanel() || !invokeBool(ui, "IsGameMenuEnabled", 0, nullptr, false)) { LOGI("menu: the game does not allow it now"); return false; }
    void* pa[1] = {(void*)pl};
    invokeOk(spm, "SetUIInputsPlayer", 1, pa);
    int layer = 3, mode = 2; bool in = true;
    void* a[5] = {(void*)typeObj(gmK), &layer, nullptr, &in, &mode};
    P view = invoke(ui, 0, "InstantiatePreloadedScreen", 5, a);
    if (view) { animKick(view); animKick(ptrF(view, "gameMenuHomeSection")); }
    LOGI("menu: %s", view ? "opened" : "failed");
    return view != 0;
}
bool openMap() {
    // InGameTouchUI.OnMapButton is what the game's own on-screen map button runs. It only exists while the game
    // offers that button (worldMapBtn active), so the same condition is kept here. It ends by toggling the touch
    // drawer the button lives in; put the drawer back the way it was.
    P ui = touchUI(), btn = ptrF(ui, "worldMapBtn");
    bool offered = btn && invokeBool(btn, "get_activeSelf", 0, nullptr, false);
    if (!ui || !offered || !canOpenPanel()) { LOGI("map: not available now (ui %d offered %d)", ui != 0, offered); return false; }
    bool was = invokeBool(ui, "IsMenuOpen", 0, nullptr, false);
    bool ok = invokeOk(ui, "OnMapButton", 0, nullptr);
    if (invokeBool(ui, "IsMenuOpen", 0, nullptr, false) != was) invokeOk(ui, "ToggleMenu", 0, nullptr);
    LOGI("map: %s", ok ? "opened" : "failed");
    return ok;
}
bool pressTool(const char* field) {
    // "inventoryBtn" (openGameMenu) is deliberately not reachable: InstantiatePreloadedScreen(typeof(GameMenu)...) gave a
    // half-initialised menu on the top screen (2026-11-17). The bottom screen has its own menus instead.
    if (!strcmp(field, "worldMapBtn")) return openMap();
    LOGI("press %s: not supported", field);     // camp is a held input in the game (CampingDeployPartyTrigger), no single call
    return false;
}
int g_wantSection = 0; long g_wantSectionUntil = 0;
void sectionTick() {            // after the game menu has opened, switch it to the requested section
    extern long g_ticks;
    if (!g_wantSection) return;
    if (g_ticks > g_wantSectionUntil) { g_wantSection = 0; return; }
    for (P gm : findAll("GameMenu")) {
        if (!gm || !valF<bool>(gm, "isReady")) continue;
        int id = g_wantSection; void* a[2] = {&id, nullptr};
        LOGI("menu section %d: %s", id, invokeOk(gm, "ChangeSection", 2, a) ? "ok" : "failed");
        g_wantSection = 0;
        return;
    }
}
void toolsState() {
    P ui = touchUI();
    auto on = [&](const char* fld) { P go = ptrF(ui, fld); return go ? invokeBool(go, "get_activeSelf", 0, nullptr, false) : false; };
    g_tools = std::string("{") + kvb("ui", ui != 0) + "," + kvb("menu", on("inventoryBtn")) + "," + kvb("map", on("worldMapBtn")) + "," + kvb("camp", false) + "}";   // camp: hold-to-deploy in the game, not wired
}

// ---- companion menus (2026-11-17): Party / Equip / Skills / Items / Relics on the bottom screen.
// Data is read with the game's own getters and written to files/duo_menu.json ("md:refresh"); the snapshot carries
// "mdSeq" so the Java side knows when to re-read. Changes ("do:...") call exactly what the game's menu calls
// (read from the binary, see NOTES.md "Companion menus") and only while the game itself would open its menu.
int g_mdSeq = 0;
std::string g_mdMsg;                       // last action result, shown on the bottom screen
std::string iconOf(P sprite) {             // sprite name; queued for extraction into duocache if not there yet
    if (!sprite) return "";
    std::string nm = safeName(objName(sprite));
    if (nm.empty()) return "";
    if (!g_cached[nm]) {
        g_cached[nm] = true;
        if (!fileExists(g_dir + "/duocache/" + nm + ".bin")) g_cacheQ.push_back({nm, sprite});
    }
    return nm;
}
bool menuAllowed() {
    if (ptrF(managerInstance("CombatManager"), "currentEncounter")) return false;
    P ui = managerInstance("UIManager");
    if (readyGameMenu()) return false;                          // the game's own menu is open: leave it alone
    return ui && canOpenPanel() && invokeBool(ui, "IsGameMenuEnabled", 0, nullptr, false);
}
P charIdAddr(const std::string& id) {                           // address of the CharacterDefinitionId inside currentParty
    for (P a : listElems(ptrF(managerInstance("PlayerPartyManager"), "currentParty"))) if (str(rd<P>(a)) == id) return a;
    return 0;
}
P charData(P idAddr) {
    P csm = managerInstance("CharacterStatsManager");
    void* a[1] = {(void*)idAddr};
    return csm && idAddr ? invoke(csm, 0, "GetCharacterData", 1, a) : 0;
}
struct ItemHit { P keyAddr = 0; P item = 0; };
ItemHit itemByGuid(const std::string& guid) {
    for (auto& e : dictEntries(ptrF(managerInstance("InventoryManager"), "allInventoryItemData")))
        if (str(rd<P>(e.key)) == guid) return {e.key, rd<P>(e.val)};
    return {};
}
bool isA(P obj, const char* cls) {
    void* k = findClass(cls);
    return obj && k && api.class_is_subclass_of((void*)klassOf(obj), k, false);
}
std::string itemDesc(P item) {
    std::string d = item ? str(invoke(item, 0, "GetDescriptionText", 0, nullptr)) : "";
    return d.empty() && item ? loc(fieldAddr(item, "descriptionLocalizationId")) : d;
}
int ownedQty(P keyAddr) { void* a[1] = {(void*)keyAddr}; return invokeInt(managerInstance("InventoryManager"), "GetOwnedQuantity", 1, a, 0); }
// how many copies of a gear piece are worn across all characters (the game's "available" = owned - worn)
int wornCount(P item) {
    int n = 0;
    for (auto& e : dictEntries(ptrF(managerInstance("CharacterStatsManager"), "characterDataByCharacter"))) {
        P d = rd<P>(e.val);
        if (!d) continue;
        if (ptrF(d, "equippedWeapon") == item) n++;
        if (ptrF(d, "equippedArmor") == item) n++;
        if (ptrF(d, "equippedGroupTrinket") == item) n++;
        for (P t : listRefs(ptrF(d, "equippedTrinkets"))) if (t == item) n++;
    }
    return n;
}
// slot keys: weapon, armor, trinket0.., group
P slotItem(P data, const std::string& slot) {
    if (slot == "weapon") return ptrF(data, "equippedWeapon");
    if (slot == "armor") return ptrF(data, "equippedArmor");
    if (slot == "group") return ptrF(data, "equippedGroupTrinket");
    if (slot.rfind("trinket", 0) == 0) { auto v = listRefs(ptrF(data, "equippedTrinkets")); size_t i = atoi(slot.c_str() + 7); return i < v.size() ? v[i] : 0; }
    return 0;
}
bool fitsSlot(P item, const std::string& slot) {
    if (slot == "weapon") return isA(item, "CharacterWeapon");
    if (slot == "armor") return isA(item, "CharacterArmor");
    if (!isA(item, "CharacterTrinket")) return false;
    return valF<bool>(item, "isGroupTrinket") == (slot == "group");
}
std::string gearJson(P item, P keyAddr) {
    std::string s = "{" + kv("ref", item ? str(ptrF(item, "guid")) : "") + "," + kv("name", item ? itemName(item) : "") + "," + kv("icon", item ? iconOf(ptrF(item, "smallIcon")) : "");
    if (item) {
        s += "," + kv("desc", itemDesc(item));
        if (isA(item, "CharacterWeapon")) s += "," + kvi("atk", valF<int>(item, "physicalAttack")) + "," + kvi("matk", valF<int>(item, "magicalAttack"));
        if (isA(item, "CharacterArmor")) s += "," + kvi("def", valF<int>(item, "physicalDefense")) + "," + kvi("mdef", valF<int>(item, "magicalDefense"));
        if (keyAddr) s += "," + kvi("qty", ownedQty(keyAddr));
    }
    return s + "}";
}
std::string choicesJson(const std::string& charId, const std::string& slot) {
    P idAddr = charIdAddr(charId), data = charData(idAddr);
    if (!data) return "null";
    P cur = slotItem(data, slot);
    std::string out = "{" + kv("char", charId) + "," + kv("slot", slot) + ",\"list\":[";
    bool first = true;
    for (auto& e : dictEntries(ptrF(managerInstance("InventoryManager"), "allInventoryItemData"))) {
        P item = rd<P>(e.val);
        if (!item || !fitsSlot(item, slot)) continue;
        int qty = ownedQty(e.key);
        if (qty <= 0) continue;
        void* a[1] = {(void*)idAddr};
        if (!invokeBool(item, "CanEquipToCharacter", 1, a, false)) continue;
        int freeN = qty - wornCount(item);
        if (freeN <= 0 && item != cur) continue;
        std::string g = gearJson(item, e.key);
        g.pop_back();
        out += (first ? "" : ",") + g + "," + kvb("cur", item == cur) + "," + kvi("free", freeN) + "}";
        first = false;
    }
    return out + "]}";
}
std::string g_mdChoices = "null";
// The game's own rules for its menu list (GameMenuHomeSection.SetHomeState): Relics is greyed out until a relic is owned
// (RelicManager.IsPlayerOwningRelics) and Order unless CanChangeOrder() says so (leader standing normally, leader is one
// of the two main heroes, two story flags clear). CanChangeOrder never reads `this` (checked in the disassembly), so it
// is run with a stand-in object instead of a menu that is not open.
bool relicsAllowed() { P rm = managerInstance("RelicManager"); return rm && invokeBool(rm, "IsPlayerOwningRelics", 0, nullptr, false); }
bool orderAllowed() {
    void* k = findClass("GameMenuHomeSection");
    void* m = k ? api.class_get_method_from_name(k, "CanChangeOrder", 0) : nullptr;
    P ppm = managerInstance("PlayerPartyManager");
    P r = m && ppm ? call(m, ppm, nullptr) : 0;
    return r && *(bool*)api.object_unbox((void*)r);
}
void buildMenuData() {
    if (g_dir.empty()) return;
    P ppm = managerInstance("PlayerPartyManager"), cm = managerInstance("CombatManager"), cdm = managerInstance("CharacterDefinitionManager");
    P rm = managerInstance("RelicManager"), im = managerInstance("InventoryManager");
    if (!ppm || !im) return;
    std::string s = "{" + kvi("seq", ++g_mdSeq) + "," + kvb("ok", menuAllowed()) + "," + kvb("gameMenu", readyGameMenu() != 0) + "," + kvb("relicsOk", relicsAllowed()) + "," + kvb("orderOk", orderAllowed()) +
                    "," + kv("msg", g_mdMsg);
    s += ",\"chars\":[";
    bool first = true;
    for (P idAddr : listElems(ptrF(ppm, "currentParty"))) {
        std::string id = str(rd<P>(idAddr));
        P data = charData(idAddr);
        if (id.empty() || !data) continue;
        void* a1[1] = {(void*)idAddr};
        P def = cdm ? invoke(cdm, 0, "GetCharacterDefinition", 1, a1) : 0;
        auto stat = [&](int st) { int m = -1; void* a[2] = {&st, &m}; return invokeInt(data, "GetStat", 2, a, 0); };
        P pch = invoke(ppm, 0, "GetPartyCharacter", 1, a1);
        P pgo = pch ? invoke(pch, 0, "get_gameObject", 0, nullptr) : 0;
        bool present = pgo && invokeBool(pgo, "get_activeInHierarchy", 0, nullptr, false);
        std::string row = "{" + kv("id", id) + "," + kv("name", def ? loc(fieldAddr(def, "nameLocId")) : id) + "," + kvb("combat", invokeBool(ppm, "IsInCombatParty", 1, a1, false)) + "," + kvb("present", present) +
            "," + kvi("hp", valF<int>(data, "currentHP")) + "," + kvi("hpMax", stat(0)) + "," + kvi("mp", valF<int>(data, "currentSP")) + "," + kvi("mpMax", stat(1)) +
            "," + kvi("atk", stat(3)) + "," + kvi("def", stat(4)) + "," + kvi("matk", stat(5)) + "," + kvi("mdef", stat(6)) + ",\"slots\":[";
        auto slot = [&](const std::string& k, P item) { std::string g = gearJson(item, 0); g.pop_back(); return g + "," + kv("k", k) + "}"; };
        row += slot("weapon", ptrF(data, "equippedWeapon")) + "," + slot("armor", ptrF(data, "equippedArmor"));
        auto tr = listRefs(ptrF(data, "equippedTrinkets"));
        for (size_t i = 0; i < tr.size(); i++) row += "," + slot("trinket" + std::to_string(i), tr[i]);
        row += "," + slot("group", ptrF(data, "equippedGroupTrinket")) + "],\"skills\":[";
        P fd = cm ? invoke(cm, 0, "GetFighterDefinition", 1, a1) : 0;
        bool f2 = true; int idx = -1;
        for (P d : listRefs(ptrF(fd, "specialMoves"))) {
            idx++;
            if (!d) continue;
            bool vp = false; void* ca[1] = {&vp};
            if (!invokeBool(d, "CanBeShown", 1, ca, false)) continue;
            bool unlocked = invokeBool(d, "IsUnlocked", 0, nullptr, false);
            if (!unlocked) continue;                                  // nothing the game has not unlocked
            bool field = invokeBool(d, "IsUsableOutOfCombat", 0, nullptr, false);
            row += std::string(f2 ? "" : ",") + "{" + kvi("i", idx) + "," + kv("n", loc(fieldAddr(d, "nameLocId"))) + "," + kv("desc", loc(fieldAddr(d, "descriptionLocId"))) +
                   "," + kvi("cat", valF<int>(d, "moveCategory")) + "," + kvi("cp", valF<int>(d, "comboPointCost")) + "," + kvi("cost", invokeInt(d, "GetMPCost", 1, a1, 0)) + "," + kv("icon", iconOf(ptrF(d, "moveIcon"))) +
                   "," + kvb("field", field) + "," + kvb("aoe", field && invokeBool(d, "IsAOE", 0, nullptr, false)) + "," + kvi("heal", field ? invokeInt(d, "GetHealAmount", 1, a1, 0) : 0) + "}";
            f2 = false;
        }
        s += (first ? "" : ",") + row + "]}";
        first = false;
    }
    s += "],\"items\":[";
    first = true;
    std::string relics;
    for (auto& e : dictEntries(ptrF(im, "allInventoryItemData"))) {
        P item = rd<P>(e.val);
        if (!item) continue;
        int qty = ownedQty(e.key);
        if (qty <= 0) continue;
        if (isA(item, "RelicItem")) {
            void* ra[1] = {(void*)item};
            void* m = methodSig((void*)klassOf(rm), "IsRelicEnabled", {"RelicItem"});
            P on = rm ? call(m, rm, ra) : 0;
            relics += std::string(relics.empty() ? "" : ",") + "{" + kv("ref", str(ptrF(item, "guid"))) + "," + kv("name", itemName(item)) + "," + kv("icon", iconOf(ptrF(item, "smallIcon"))) +
                      "," + kv("desc", itemDesc(item)) + "," + kv("full", str(invoke(item, 0, "GetFullDescriptionText", 0, nullptr))) +
                      "," + kvb("on", on && *(bool*)api.object_unbox((void*)on)) + "," + kvb("can", invokeBool(item, "CanBeEnabled", 0, nullptr, false)) +
                      "," + kvi("pri", valF<int>(item, "orderPriority")) + "}";
            continue;
        }
        bool cons = isA(item, "ConsumableItem");
        s += std::string(first ? "" : ",") + "{" + kv("ref", str(ptrF(item, "guid"))) + "," + kv("name", itemName(item)) + "," + kvi("qty", qty) + "," + kv("cat", className(item)) +
             "," + kv("icon", iconOf(ptrF(item, "smallIcon"))) + "," + kv("desc", itemDesc(item)) + "," + kvi("pri", valF<int>(item, "orderPriority")) +
             "," + kvb("use", cons && invokeBool(item, "CanConsume", 0, nullptr, false)) +
             "," + kv("fx", cons ? str(invoke(item, 0, "GetStatDescriptionText", 0, nullptr)) : "") + "," + kvb("aoe", cons && fieldOff(klassOf(item), "isAOE") >= 0 && valF<bool>(item, "isAOE")) + "}";
        first = false;
    }
    s += "],\"relics\":[" + relics + "],\"choices\":" + g_mdChoices + "}";
    std::string f = g_dir + "/duo_menu.json";
    FILE* o = fopen((f + ".tmp").c_str(), "w");
    if (!o) return;
    fwrite(s.data(), 1, s.size(), o); fclose(o);
    rename((f + ".tmp").c_str(), f.c_str());
    g_mdMsg.clear();                 // a message is shown once, not again on every refresh
}
std::vector<std::string> words(const std::string& s) {
    std::vector<std::string> v; size_t i = 0;
    while (i < s.size()) { size_t j = s.find(' ', i); if (j == std::string::npos) j = s.size(); if (j > i) v.push_back(s.substr(i, j - i)); i = j + 1; }
    return v;
}
std::string staticCharId(const char* field) {
    void* k = findClass("CharacterDefinitionId");
    void* f = k ? api.class_get_field_from_name(k, field) : nullptr;
    P v = 0;
    if (f) api.field_static_get_value(f, &v);
    return str(v);
}
// Is anyone it would reach below full HP (or MP, for food)? Keeps a tap from wasting food or MP on a full party.
bool needsCare(P targetAddr, bool aoe, bool mpToo) {
    auto one = [&](P idA) {
        P d = charData(idA);
        if (!d) return false;
        int z = 0, o = 1, m = -1; void* ha[2] = {&z, &m}; void* ma[2] = {&o, &m};
        return valF<int>(d, "currentHP") < invokeInt(d, "GetStat", 2, ha, 0) || (mpToo && valF<int>(d, "currentSP") < invokeInt(d, "GetStat", 2, ma, 0));
    };
    if (!aoe) return one(targetAddr);
    for (P idA : listElems(ptrF(managerInstance("PlayerPartyManager"), "currentParty"))) if (one(idA)) return true;
    return false;
}
// do:equip <char> <slot> <guid|->   do:swap <charA> <charB>   do:relic <guid>   do:use <guid> <char>   do:skill <char> <idx> <target>
void doMenu(const std::string& a) {
    auto w = words(a);
    g_mdMsg.clear();
    if (w.empty()) return;
    if (!menuAllowed()) { g_mdMsg = readyGameMenu() ? "Close the game's menu first" : "Not available right now"; LOGI("do: refused, the game's menu is not available now (%s)", a.c_str()); buildMenuData(); return; }
    P ppm = managerInstance("PlayerPartyManager"), im = managerInstance("InventoryManager");
    bool ok = false;
    if (w[0] == "do:equip" && w.size() == 4) {
        P idAddr = charIdAddr(w[1]), data = charData(idAddr);
        const std::string& slot = w[2];
        bool trinket = slot.rfind("trinket", 0) == 0;
        if (data && w[3] == "-") {                               // take off: trinkets only, as in the game's list
            if (trinket) { int n = atoi(slot.c_str() + 7); void* ra[1] = {&n}; ok = invokeOk(data, "RemoveCharacterTrinket", 1, ra); }
            else if (slot == "group") ok = invokeOk(data, "RemoveGroupTrinket", 0, nullptr);
        } else if (data) {
            ItemHit it = itemByGuid(w[3]);
            void* ca[1] = {(void*)idAddr};
            bool can = it.item && fitsSlot(it.item, slot) && invokeBool(it.item, "CanEquipToCharacter", 1, ca, false) &&
                       (ownedQty(it.keyAddr) - wornCount(it.item) > 0) && slotItem(data, slot) != it.item;
            if (can) {
                void* ia[1] = {(void*)it.item};
                if (slot == "weapon") ok = invokeOk(data, "SetEquippedWeapon", 1, ia);
                else if (slot == "armor") ok = invokeOk(data, "SetEquippedArmor", 1, ia);
                else if (slot == "group") { void* m = methodSig((void*)klassOf(data), "EquipGroupTrinket", {"CharacterTrinket"}); ok = m && (call(m, data, ia), true); }
                else if (trinket) {
                    int n = atoi(slot.c_str() + 7); void* ta[2] = {&n, (void*)it.item};
                    void* m = methodSig((void*)klassOf(data), "EquipCharacterTrinket", {"System.Int32", "CharacterTrinket"});
                    ok = m && n >= 0 && n < (int)listRefs(ptrF(data, "equippedTrinkets")).size() && (call(m, data, ta), true);
                }
            }
        }
        if (ok) g_mdChoices = "null";
    } else if (w[0] == "do:swap" && w.size() == 3 && w[1] != w[2]) {
        // GameMenuCharacterPreviewPanel.CompleteChangeOrder: Zale<->Valere swaps the leader, anything else swaps places
        P aAddr = charIdAddr(w[1]), bAddr = charIdAddr(w[2]), spm = managerInstance("SinglePlayerPlusManager");
        if (aAddr && bAddr && spm && ppm && !orderAllowed()) g_mdMsg = "The order cannot be changed right now";
        else if (aAddr && bAddr && spm && ppm) {
            std::string zale = staticCharId("Zale"), valere = staticCharId("Valere");
            bool mainPair = (w[1] == zale || w[1] == valere) && (w[2] == zale || w[2] == valere) && !zale.empty();
            invokeOk(spm, "UnassignAllCharacters", 0, nullptr);
            P table = rd<P>(spm + 0x118);
            if (table) invokeOk(table, "ClearLastAssignedCharacterByPlayer", 0, nullptr);
            if (mainPair) {
                ok = invokeOk(ppm, "SwapPartyLeader", 0, nullptr);
                void* pa[1] = {(void*)firstPlayer()};
                P sp = invoke(spm, 0, "GetSinglePlayerPlusPlayer", 1, pa);
                if (sp) { void* ua[1] = {(void*)sp}; invokeOk(spm, "UnregisterLeader", 1, ua); void* ra[2] = {(void*)sp, (void*)ptrF(ppm, "leader")}; invokeOk(spm, "RegisterLeader", 2, ra); }
            } else {
                // copies: the swap rewrites the list these addresses point into
                P av = rd<P>(aAddr), bv = rd<P>(bAddr); bool reset = true;
                void* sa[3] = {&av, &bv, &reset};
                ok = invokeOk(ppm, "SwapCharacterOrderInParty", 3, sa);
            }
            bool t = true; void* aa[2] = {&t, &t};
            invokeOk(spm, "AssignCharactersToPlayers", 2, aa);
            // The game then runs GameMenuCharacterPreviewPanel.FixPopJumpIssues (moves a member who ended up on a ledge
            // edge). Its code never reads `this` (checked in the disassembly, 2026-11-17), so it can be run without the
            // menu panel existing; any live object stands in for `this`.
            void* pk = findClass("GameMenuCharacterPreviewPanel");
            void* fm = pk ? api.class_get_method_from_name(pk, "FixPopJumpIssues", 0) : nullptr;
            if (fm) call(fm, ppm, nullptr);
        }
    } else if (w[0] == "do:relic" && w.size() == 2) {
        ItemHit it = itemByGuid(w[1]);
        P rm = managerInstance("RelicManager");
        if (it.item && rm && isA(it.item, "RelicItem") && ownedQty(it.keyAddr) > 0 && invokeBool(it.item, "CanBeEnabled", 0, nullptr, false)) {
            void* ra[1] = {(void*)it.item};
            void* m = methodSig((void*)klassOf(rm), "ToggleRelic", {"RelicItem"});
            ok = m && (call(m, rm, ra), true);
        }
    } else if (w[0] == "do:use" && w.size() == 3) {
        // InventoryPreviewPanel.SubmitConsumeItem: item.Consume(target), then one is taken out of the inventory
        ItemHit it = itemByGuid(w[1]);
        P tAddr = charIdAddr(w[2]);
        bool aoe = it.item && fieldOff(klassOf(it.item), "isAOE") >= 0 && valF<bool>(it.item, "isAOE");
        if (it.item && tAddr && !needsCare(tAddr, aoe, true)) g_mdMsg = "It would have no effect";
        else if (it.item && tAddr && isA(it.item, "ConsumableItem") && ownedQty(it.keyAddr) > 0 && invokeBool(it.item, "CanConsume", 0, nullptr, false)) {
            P tv = rd<P>(tAddr); void* ca[1] = {&tv};
            ok = invokeOk(it.item, "Consume", 1, ca);
            if (ok) { int one = 1; void* ra[2] = {(void*)it.keyAddr, &one}; invokeOk(im, "RemoveInventoryItem", 2, ra); }
        }
    } else if (w[0] == "do:skill" && w.size() == 4) {
        // GameMenuCharacterSectionSkillTargetSelectionPanel.UseSkillOnTarget
        P cAddr = charIdAddr(w[1]), tAddr = charIdAddr(w[3]), data = charData(cAddr), cm = managerInstance("CombatManager");
        void* a1[1] = {(void*)cAddr};
        P fd = cm && cAddr ? invoke(cm, 0, "GetFighterDefinition", 1, a1) : 0;
        auto moves = listRefs(ptrF(fd, "specialMoves"));
        size_t i = atoi(w[2].c_str());
        P d = i < moves.size() ? moves[i] : 0;
        if (d && data && tAddr && invokeBool(d, "IsUnlocked", 0, nullptr, false) && invokeBool(d, "IsUsableOutOfCombat", 0, nullptr, false)) {
            int cost = invokeInt(d, "GetMPCost", 1, a1, 9999), sp = invokeInt(data, "GetCurrentSP", 0, nullptr, 0);
            if (!needsCare(tAddr, invokeBool(d, "IsAOE", 0, nullptr, false), false)) g_mdMsg = "It would have no effect";
            else if (cost <= sp) {
                int heal = invokeInt(d, "GetHealAmount", 1, a1, 0);
                int left = sp - cost; void* sa[1] = {&left};
                ok = invokeOk(data, "SetCurrentSP", 1, sa);
                auto healOne = [&](P idA) {
                    P td = charData(idA);
                    if (!td) return;
                    int z = 0, m = -1; void* ga[2] = {&z, &m};
                    int hp = std::min(valF<int>(td, "currentHP") + heal, invokeInt(td, "GetStat", 2, ga, 0));
                    void* ha[1] = {&hp};
                    invokeOk(td, "SetCurrentHP", 1, ha);
                };
                if (invokeBool(d, "IsAOE", 0, nullptr, false)) { for (P idA : listElems(ptrF(ppm, "currentParty"))) healOne(idA); }
                else healOne(tAddr);
            } else g_mdMsg = "Not enough MP";
        }
    }
    if (!ok && g_mdMsg.empty()) g_mdMsg = "The game refused that";
    LOGI("%s -> %s", a.c_str(), ok ? "done" : "refused");
    buildMenuData();
}
void menuCmd(const std::string& a) {
    if (a == "md:refresh") { buildMenuData(); return; }
    if (a.rfind("md:equip ", 0) == 0) { auto w = words(a); g_mdChoices = w.size() == 3 ? choicesJson(w[1], w[2]) : "null"; buildMenuData(); return; }
    if (a == "md:close") { g_mdChoices = "null"; g_mdMsg.clear(); return; }
    if (a.rfind("do:", 0) == 0) doMenu(a);
}

// ---- battle extras (main thread): what each character can break, Live Mana, ultimate gauge, KO countdown, target.
bool invokeBool(P obj, const char* name, int argc, void** args, bool dflt) {
    P r = invoke(obj, 0, name, argc, args);
    return r ? *(bool*)api.object_unbox((void*)r) : dflt;
}
// Lock damage types of a move, asked from the game's own CombatMove.GetLocksDamageTypes.
std::string moveTypes(P def, P actor = 0) {
    auto it = g_moveTypes.find(def);
    if (it != g_moveTypes.end()) return it->second;
    std::string out = "[";
    static void* listK = nullptr;
    if (!listK) {
        void* hk = findClass("HitData");
        void* f = hk ? api.class_get_field_from_name(hk, "locksDamageTypes") : nullptr;
        if (f) listK = api.class_from_type(api.field_get_type(f));
    }
    // basic attacks: ask the character's own live attack object (the prefab one needs an owner and throws)
    P mv = actor ? invoke(actor, 0, "GetBasicAttack", 0, nullptr) : 0;
    if (!mv) mv = invoke(def, 0, "GetCombatMoveComponent", 0, nullptr);
    if (listK && mv) {
        P list = (P)api.object_new(listK);
        if (invokeOk(list, ".ctor", 0, nullptr)) {
            void* a[1] = {(void*)list};
            if (invokeOk(mv, "GetLocksDamageTypes", 1, a) || (invokeOk(list, "Clear", 0, nullptr) && invokeOk(mv, "GetDamageDamageTypes", 1, a))) {
                P items = ptrF(list, "_items"); int n = valF<int>(list, "_size");
                for (int i = 0; items && i < n && i < 16; i++) out += std::string(i ? "," : "") + q(damageName(rd<int>(items + 0x20 + (P)i * 4)));
            }
        }
    }
    out += "]";
    g_moveTypes[def] = out;
    return out;
}
void battleExtras() {
    static P lastEnc = 0, hud = 0, bscr = 0;
    P cm = managerInstance("CombatManager");
    P enc = ptrF(cm, "currentEncounter");
    if (!enc) { g_bx.clear(); lastEnc = 0; return; }
    if (enc != lastEnc) {
        lastEnc = enc; g_moveTypes.clear(); hud = 0; bscr = 0;
        auto h = findAll("InGameHud"); if (!h.empty()) hud = h[0];
        auto b = findAll("BattleScreen"); if (!b.empty()) bscr = b[0];
    }
    // the two text boxes the game shows at the top: the info bar and the instruction banner ("Hold ... for power!")
    auto boxText = [&](const char* field) -> std::string {
        P box = ptrF(bscr, field);
        P tmp = ptrF(ptrF(box, "textLocalizer"), "textMeshProText");
        P go = tmp ? invoke(tmp, 0, "get_gameObject", 0, nullptr) : 0;
        if (!go || !invokeBool(go, "get_activeInHierarchy", 0, nullptr, false)) return "";
        return str(ptrF(tmp, "m_text"));
    };
    std::string infoTxt = boxText("combatInfoBox"), dialogTxt = boxText("dialogBox");
    fadeHud(hud, bscr, activeList(enc));
    float ultFill = valF<float>(ptrF(ptrF(ptrF(ptrF(hud, "playerBattleUI"), "comboPointsPanel"), "ultMeter"), "meterFill"), "m_FillAmount", -1.f);
    std::string moves = "{", ko = "{", off = "[";
    bool manaOn = false, firstA = true, firstK = true;
    for (P slot : listRefs(ptrF(enc, "playerSlots"))) {
        P actor = ptrF(slot, "currentActor");
        if (!actor) continue;
        P idAddr = fieldAddr(actor, "characterDefinitionId");
        std::string id = str(rd<P>(idAddr));
        if (id.empty()) continue;
        if (valF<bool>(ptrF(actor, "liveManaHandler"), "canAbsorbLiveMana")) manaOn = true;
        // greyed panel on the game's HUD (already acted this round / cannot act)
        P bp = ptrF(ptrF(actor, "battleUIPanel"), "portrait");
        if (bp && !valF<bool>(bp, "characterEnabled", true)) off += std::string(off.size() > 1 ? "," : "") + q(id);
        if (valF<bool>(actor, "dead")) {
            ko += std::string(firstK ? "" : ",") + q(id) + ":" + std::to_string(valF<int>(ptrF(actor, "deathCountdown"), "currentStarCount"));
            firstK = false;
        }
        P fd = ptrF(actor, "fighterDefinition");
        std::vector<P> defs;
        if (P b = ptrF(fd, "basicAttackMove")) defs.push_back(b);
        for (P d : listRefs(ptrF(fd, "specialMoves"))) if (d) defs.push_back(d);
        std::string arr = "[";
        bool firstM = true;
        for (P d : defs) {
            int cat = valF<int>(d, "moveCategory");
            if (cat != 0) {                       // skills and ultimates only once the game has given them to the player
                bool yes = true; void* sa[1] = {&yes};
                if (!invokeBool(d, "IsUnlocked", 0, nullptr, false) || !invokeBool(d, "CanBeShown", 1, sa, false)) continue;
            }
            void* ua[1] = {(void*)actor};
            bool usable = invokeBool(d, "CanBeUsed", 1, ua, true);
            void* ca[1] = {(void*)idAddr};
            int cost = cat == 0 ? 0 : invokeInt(d, "GetMPCost", 1, ca, 0);
            arr += std::string(firstM ? "" : ",") + "{" + kv("n", loc(fieldAddr(d, "nameLocId"))) + "," + kvi("cat", cat) + "," + kvi("c", cost) + "," +
                   kvb("ok", usable) + ",\"t\":" + moveTypes(d, cat == 0 ? actor : 0) + "}";
            firstM = false;
        }
        moves += std::string(firstA ? "" : ",") + q(id) + ":" + arr + "]";
        firstA = false;
    }
    P lmh = ptrF(enc, "liveManaHandler");
    int fieldMana = (int)listRefs(ptrF(lmh, "smallLiveManaParticles")).size(), bigMana = (int)listRefs(ptrF(lmh, "bigLiveManaParticles")).size();
    P csm = managerInstance("CharacterStatsManager");
    bool hasUlt = invokeBool(managerInstance("ProgressionManager"), "DoesPartyContainPlayerWithUlt", 0, nullptr, false);
    int ultMax = valF<int>(ptrF(cm, "globalCombatSettings"), "maxUltPoints");
    bool comboShown = true, ultShown = false;
    if (P panel = ptrF(ptrF(hud, "playerBattleUI"), "comboPointsPanel")) {
        P go = invoke(panel, 0, "get_gameObject", 0, nullptr);
        if (go) comboShown = invokeBool(go, "get_activeInHierarchy", 0, nullptr, true);
        P um = ptrF(panel, "ultMeter");
        P ugo = um ? invoke(um, 0, "get_gameObject", 0, nullptr) : 0;
        if (ugo) ultShown = invokeBool(ugo, "get_activeInHierarchy", 0, nullptr, false);
    }
    // enemy HP is only shown by the game when a relic adds ShowEnemyHPModifier
    bool showHP = false;
    std::vector<P> handlers = listRefs(ptrF(csm, "playerGameplayModifiersHandlers"));
    handlers.push_back(ptrF(csm, "globalGameplayModifiersHandler"));
    for (P hd : handlers) for (P mod : listRefs(ptrF(hd, "gameplayModifiers"))) if (className(mod) == "ShowEnemyHPModifier") showHP = true;
    // target under the game's cursor while a target is being chosen
    int tgt = -1; std::string tgtId;
    std::string stName = className(ptrF(ptrF(enc, "stateMachine"), "currentState"));
    if (stName.find("Target") != std::string::npos) {
        for (P scr : findAll("CombatTargetSelectionScreen")) {
            if (!valF<bool>(scr, "active")) continue;
            P owner = ptrF(ptrF(scr, "currentTarget"), "owner");
            if (!owner) continue;
            int idx = 0;
            for (P slot : listRefs(ptrF(enc, "enemySlots"))) { P e = ptrF(slot, "enemy"); if (!e) continue; if (e == owner) tgt = idx; idx++; }
            if (tgt < 0 && valF<bool>(owner, "isPlayer")) tgtId = str(rd<P>(fieldAddr(owner, "characterDefinitionId")));
            break;
        }
    }
    g_bx = "{\"moves\":" + moves + "},\"ko\":" + ko + "},\"off\":" + off + "]," + kvb("manaOn", manaOn) + "," + kvi("fieldMana", fieldMana) + "," + kvi("bigMana", bigMana) + "," +
           kvb("hasUlt", hasUlt) + "," + kvi("ult", valF<int>(csm, "ultPoints")) + "," + kvi("ultMax", ultMax) + "," + kvb("comboShown", comboShown) + "," + kvb("ultShown", ultShown) + "," + kvb("showHP", showHP) + "," +
           kvi("tgt", tgt) + "," + kv("info", infoTxt) + "," + kv("dialog", dialogTxt) + "," + kvi("ultFill", (long)(ultFill * 1000)) + "," + kv("tgtId", tgtId) + "," + kv("st", stName) + "}";
}

// ---- exploration probe (debug, "scan:level"): everything a dungeon map could be built from, written to
// <files>/duo_scan.json: walkable floor sampled from the level's NavMesh around the party, the level's real
// objects (save points, camps, chests, exits), and the current story activity / objective.
bool posOf(P comp, float* out) {
    P tr = comp ? invoke(comp, 0, "get_transform", 0, nullptr) : 0;
    P pos = tr ? invoke(tr, 0, "get_position", 0, nullptr) : 0;
    if (!pos) return false;
    memcpy(out, api.object_unbox((void*)pos), 12);
    return true;
}
void scanLevel(float radius, bool withObjects) {
    if (g_dir.empty() || !g_leaderOk) { LOGE("scan: not ready"); return; }
    FILE* f = fopen((g_dir + "/duo_scan.tmp").c_str(), "w");
    if (!f) return;
    P lvm = managerInstance("LevelManager");
    fprintf(f, "{\"pos\":[%.2f,%.2f,%.2f],\"level\":%s", g_leaderPos[0], g_leaderPos[1], g_leaderPos[2], q(str(rd<P>(fieldAddr(lvm, "currentLevel")))).c_str());
    // objects (only on the full scan: FindObjectsOfType is too slow to run every few seconds)
    if (withObjects) fprintf(f, ",\"objects\":[");
    bool first = true;
    for (const char* cls : {"Savepoint", "CampingSpot", "LevelLoadingZone", "InLevelTeleporter"}) {
        if (!withObjects) break;
        for (P o : findAll(cls)) {
            float p[3];
            if (!o || !posOf(o, p)) continue;
            std::string extra;
            if (!strcmp(cls, "LevelLoadingZone")) {
                void* a[1] = {(void*)fieldAddr(o, "level")};
                P def = invoke(lvm, 0, "GetLevelDefinition", 1, a);
                extra = "," + kv("to", def ? loc(fieldAddr(def, "levelNameLocId")) : "");
            }
            if (!strcmp(cls, "InLevelTeleporter")) {       // doorway / staircase: where it puts the party
                float d[3];
                P tp = ptrF(o, "teleportPosition");
                P dpos = tp ? invoke(tp, 0, "get_position", 0, nullptr) : 0;
                if (!dpos) continue;
                memcpy(d, api.object_unbox((void*)dpos), 12);
                char b[96]; snprintf(b, sizeof b, ",\"d\":[%.2f,%.2f,%.2f]", d[0], d[1], d[2]);
                extra = b;
            }
            P go = invoke(o, 0, "get_gameObject", 0, nullptr);
            bool active = go ? invokeBool(go, "get_activeInHierarchy", 0, nullptr, true) : true;
            fprintf(f, "%s{%s,%s,\"p\":[%.2f,%.2f,%.2f],%s%s}", first ? "" : ",", kv("c", cls).c_str(), kv("n", objName(o)).c_str(), p[0], p[1], p[2], kvb("on", active).c_str(), extra.c_str());
            first = false;
        }
    }
    if (withObjects) fprintf(f, "]");
    // activity / objective
    P am = managerInstance("ActivityManager");
    if (am) {
        std::string an;
        P mainRef = fieldAddr(am, "mainActivity");
        void* a[1] = {(void*)mainRef};
        P ad = invoke(am, 0, "GetActivityDataByReference", 1, a);
        if (ad) an = loc(fieldAddr(ad, "activityNameLoc"));
        fprintf(f, ",\"activity\":%s,\"sub\":%d,\"objectives\":[", q(an).c_str(), valF<int>(am, "subActivityIndex"));
        P sub = invoke(am, 0, "GetSubActivity", 0, nullptr);
        bool fo = true;
        P ol = ptrF(sub, "activityObjectives");
        P items = ptrF(ol, "_items"); int n = valF<int>(ol, "_size");
        for (int i = 0; items && i < n && i < 8; i++) {
            P el = items + 0x20 + (P)i * elemSize(items);
            void* la[1] = {(void*)el};                       // MapObjective starts with its LevelReference
            P def = invoke(lvm, 0, "GetLevelDefinition", 1, la);
            fprintf(f, "%s%s", fo ? "" : ",", q(def ? loc(fieldAddr(def, "levelNameLocId")) : "?").c_str());
            fo = false;
        }
        fprintf(f, "]");
    }
    // walkable floor: NavMesh.SamplePosition on a grid; a cell counts when the nearest mesh point is (almost) straight below/above
    void* nav = findClass("UnityEngine.AI.NavMesh");
    void* sm = nav ? api.class_get_method_from_name(nav, "SamplePosition", 4) : nullptr;
    const float step = 0.5f;
    int half = (int)(radius / step), hits = 0;
    fprintf(f, ",\"step\":%.2f,\"half\":%d,\"nav\":%s,\"rows\":[", step, half, sm ? "true" : "false");
    for (int iz = half; iz >= -half && sm; iz--) {
        fprintf(f, "%s\"", iz == half ? "" : ",");
        for (int ix = -half; ix <= half; ix++) {
            float src[3] = {g_leaderPos[0] + ix * step, g_leaderPos[1], g_leaderPos[2] + iz * step};
            char hit[64] = {0}; float maxD = 6.0f; int mask = -1;
            void* a[4] = {src, hit, &maxD, &mask};
            void* exc = nullptr;
            void* r = api.runtime_invoke(sm, nullptr, a, &exc);
            char c = '.';
            if (!exc && r && *(bool*)api.object_unbox(r)) {
                float* hp = (float*)hit;
                float dx = hp[0] - src[0], dz = hp[2] - src[2];
                if (dx * dx + dz * dz < 0.3f * 0.3f) {     // floor directly here; letter = height relative to the party, half-unit steps
                    int h = (int)lroundf((hp[1] - g_leaderPos[1]) * 2);
                    c = (char)('M' + (h < -12 ? -12 : h > 12 ? 12 : h));
                    hits++;
                }
            }
            fputc(c, f);
        }
        fputc('"', f);
    }
    fprintf(f, "]}");
    fclose(f);
    rename((g_dir + "/duo_scan.tmp").c_str(), (g_dir + "/duo_scan.json").c_str());
    static int logged = 0; if (logged++ < 3) LOGI("scan: done, %d walkable cells", hits);
}

// ---- world-map probe (debug, "scan:worldmap"): how the game's own map screen is laid out, written to
// <files>/duo_worldmap.json, so the bottom screen can show the same illustrated map without the player opening it.
void rectOf(P comp, float* out) {       // anchoredPosition x,y + size w,h of a UI element
    out[0] = out[1] = out[2] = out[3] = 0;
    P tr = comp ? invoke(comp, 0, "get_transform", 0, nullptr) : 0;
    if (!tr) return;
    if (P ap = invoke(tr, 0, "get_anchoredPosition", 0, nullptr)) memcpy(out, api.object_unbox((void*)ap), 8);
    if (P sd = invoke(tr, 0, "get_sizeDelta", 0, nullptr)) memcpy(out + 2, api.object_unbox((void*)sd), 8);
}
bool activeSelf(P comp) {
    P go = comp ? invoke(comp, 0, "get_gameObject", 0, nullptr) : 0;
    return go ? invokeBool(go, "get_activeSelf", 0, nullptr, false) : false;
}
void scanWorldMap() {
    if (g_dir.empty()) return;
    FILE* f = fopen((g_dir + "/duo_worldmap.tmp").c_str(), "w");   // written whole, then renamed: the reader never sees half a file
    if (!f) return;
    bool unlocked = invokeBool(managerInstance("ProgressionManager"), "IsMapUnlocked", 0, nullptr, false);
    auto screens = findAll("MapScreen");
    fprintf(f, "{%s,\"screens\":%zu,\"sections\":[", kvb("unlocked", unlocked).c_str(), screens.size());
    bool fs = true;
    const char* hereSection = nullptr;          // the chart the party is on (not the planet picker)
    for (P scr : screens) {
        for (const char* sn : {"planetSelectionSection", "worldMap1Section", "worldMap2Section", "seaOfNightmareSection"}) {
            P sec = ptrF(scr, sn);
            if (!sec) continue;
            // the level the party is in, or on the overworld the nearest place that can be entered
            P lvm0 = managerInstance("LevelManager");
            P lvl0 = fieldAddr(lvm0, "currentLevel");
            if (g_leaderOk && className(ptrF(managerInstance("PlayerPartyManager"), "leader")).find("WorldMap") != std::string::npos) {
                float best = 1e18f;
                for (P z : findAll("LevelLoadingZone")) {
                    float p[3];
                    if (!z || !posOf(z, p)) continue;
                    float d = (p[0] - g_leaderPos[0]) * (p[0] - g_leaderPos[0]) + (p[2] - g_leaderPos[2]) * (p[2] - g_leaderPos[2]);
                    if (d < best) { best = d; lvl0 = fieldAddr(z, "level"); }
                }
            }
            fprintf(f, "%s{%s,%s,\"buttons\":[", fs ? "" : ",", kv("section", sn).c_str(), kvb("active", activeSelf(sec)).c_str());
            fs = false;
            bool fb = true;
            for (P b : listRefs(ptrF(sec, "mapLocationButtons"))) {
                if (!b) continue;
                float r[4]; rectOf(b, r);
                P img = ptrF(b, "locationImage");
                P sp = img ? invoke(img, 0, "get_sprite", 0, nullptr) : 0;
                float ir[4]; rectOf(img, ir);
                // the region's display name (e.g. "Settlers Island") and whether the party's level belongs to it
                P reg = ptrF(b, "mapLocationRegion");
                std::string rname; bool inReg = false;
                if (reg) {
                    void* la[1] = {(void*)fieldAddr(reg, "mainLevelReference")};
                    P def = invoke(lvm0, 0, "GetLevelDefinition", 1, la);
                    if (def) rname = loc(fieldAddr(def, "levelNameLocId"));
                    void* ca[1] = {(void*)lvl0};
                    inReg = lvl0 && invokeBool(reg, "ContainsLevel", 1, ca, false);
                }
                // "p" = the region's own spot in the map's cursor coordinates (MapLocationRegion.mainMapPosition). With the
                // button rectangles it lets the bottom screen work out, per chart, where cursor coordinates fall on the art.
                float rp[3] = {0, 0, 0};
                if (reg) safeRead(fieldAddr(reg, "mainMapPosition"), rp, sizeof rp);
                fprintf(f, "%s{%s,%s,%s,%s,\"r\":[%.1f,%.1f,%.1f,%.1f],\"ir\":[%.1f,%.1f,%.1f,%.1f],\"p\":[%.1f,%.1f],%s,%s}", fb ? "" : ",", kv("n", objName(b)).c_str(),
                        kv("name", rname).c_str(), kvb("here", inReg).c_str(),
                        kv("sprite", sp ? safeName(objName(sp)) : "").c_str(), r[0], r[1], r[2], r[3], ir[0], ir[1], ir[2], ir[3], rp[0], rp[1], kvb("hasp", reg != 0).c_str(), kvb("on", activeSelf(b)).c_str());
                fb = false;
            }
            fprintf(f, "],\"fog\":[");
            bool ff = true;
            for (P fg : listRefs(ptrF(sec, "mapFogOfWars"))) {
                if (!fg) continue;
                float r[4]; rectOf(fg, r);
                // fog piece: its picture, and whether the game has lifted it for this save
                static void* goK = findClass("UnityEngine.GameObject"); static void* imgK = findClass("UnityEngine.UI.Image");
                P go = invoke(fg, 0, "get_gameObject", 0, nullptr);
                void* ta[1] = {(void*)typeObj(imgK)};
                P img = go ? call(methodSig(goK, "GetComponent", {"System.Type"}), go, ta) : 0;
                P sp = img ? invoke(img, 0, "get_sprite", 0, nullptr) : 0;
                fprintf(f, "%s{%s,%s,\"r\":[%.1f,%.1f,%.1f,%.1f],%s}", ff ? "" : ",", kv("n", objName(fg)).c_str(), kv("sprite", sp ? safeName(objName(sp)) : "").c_str(),
                        r[0], r[1], r[2], r[3], kvb("lifted", invokeBool(fg, "IsUnlocked", 0, nullptr, false)).c_str());
                ff = false;
            }
            // where the party is on this section: the current level, or on the overworld the nearest place you can enter
            P lvm = managerInstance("LevelManager");
            P lvl = fieldAddr(lvm, "currentLevel");
            if (g_leaderOk && className(ptrF(managerInstance("PlayerPartyManager"), "leader")).find("WorldMap") != std::string::npos) {
                float best = 1e18f;
                for (P z : findAll("LevelLoadingZone")) {
                    float p[3];
                    if (!z || !posOf(z, p)) continue;
                    float d = (p[0] - g_leaderPos[0]) * (p[0] - g_leaderPos[0]) + (p[2] - g_leaderPos[2]) * (p[2] - g_leaderPos[2]);
                    if (d < best) { best = d; lvl = fieldAddr(z, "level"); }
                }
            }
            float mp[3] = {0, 0, 0};
            void* ma[2] = {(void*)lvl, mp};
            bool here = lvl && invokeBool(sec, "TryGetMapLevelPosition", 2, ma, false);
            if (here && strcmp(sn, "planetSelectionSection")) hereSection = sn;
            // The gold star of the game's map = where the current objective is (MapScreen.PlaceObjectiveCursor): each
            // MapObjective of the active sub-activity, placed with the section's TryGetMapLevelPosition, or at its custom
            // position. The game's own star cursors only exist while its map is open, so the spots are worked out here.
            std::string objs = "[";
            {
                P am = managerInstance("ActivityManager");
                P sub = am ? invoke(am, 0, "GetSubActivity", 0, nullptr) : 0;
                P ol = ptrF(sub, "activityObjectives");
                P oitems = ptrF(ol, "_items"); int on = valF<int>(ol, "_size");
                for (int i = 0; oitems && i < on && i < 8; i++) {
                    P el = oitems + 0x20 + (P)i * elemSize(oitems);      // MapObjective: LevelReference, bool custom @8, Vector3 @0xC
                    float op[3] = {0, 0, 0};
                    bool ok;
                    if (rd<bool>(el + 8)) ok = here && safeRead(el + 0xC, op, sizeof op);
                    else { void* oa[2] = {(void*)el, op}; ok = invokeBool(sec, "TryGetMapLevelPosition", 2, oa, false); }
                    if (!ok) continue;
                    char b[64]; snprintf(b, sizeof b, "%s[%.1f,%.1f]", objs.size() > 1 ? "," : "", op[0], op[1]);
                    objs += b;
                }
            }
            fprintf(f, "],%s,\"me\":[%.1f,%.1f,%.1f],\"obj\":%s],\"anchors\":[", kvb("here", here).c_str(), mp[0], mp[1], mp[2], objs.c_str());
            // every place you can enter from the overworld: where it is in the world and where the chart puts it.
            // The bottom screen fits these to move the marker smoothly with the party between scans.
            bool fa = true;
            for (P z : findAll("LevelLoadingZone")) {
                float wp[3], zp[3] = {0, 0, 0};
                if (!z || !posOf(z, wp)) continue;
                void* za[2] = {(void*)fieldAddr(z, "level"), zp};
                if (!invokeBool(sec, "TryGetMapLevelPosition", 2, za, false)) continue;
                fprintf(f, "%s[%.2f,%.2f,%.1f,%.1f]", fa ? "" : ",", wp[0], wp[2], zp[0], zp[1]);
                fa = false;
            }
            fprintf(f, "]}");
        }
    }
    std::string fitJson = "null", pcJson = "null";
    bool fitLive = false;
    bool onWorld = g_leaderOk && className(ptrF(managerInstance("PlayerPartyManager"), "leader")).find("WorldMap") != std::string::npos;
    for (P scr : screens) {
        P pc = ptrF(scr, "playerPositionCursor");
        P sec = hereSection ? ptrF(scr, hereSection) : 0;
        int ctxOff = fieldOff(klassOf(scr), "currentContext");
        if (!pc || !sec || ctxOff < 0) continue;
        // The hidden screen remembers the chart it last showed (often the planet picker). Point it at the party's chart
        // for the length of this call (MapContext: 1, 2, 3 = worldMap1, worldMap2, seaOfNightmare) and put it back after;
        // the game sets it again itself whenever its map is opened.
        int ctx = !strcmp(hereSection, "worldMap1Section") ? 1 : !strcmp(hereSection, "worldMap2Section") ? 2 : 3;
        int oldCtx = rd<int>(scr + ctxOff);
        P sgo = invoke(scr, 0, "get_gameObject", 0, nullptr);
        // While the game's map is open nothing is touched, but that is the moment its layout is live, so the offset
        // measured then is the trustworthy one ("fitLive"); measured on the hidden screen it came out 20-40 px off.
        bool mapOpen = sgo && invokeBool(sgo, "get_activeInHierarchy", 0, nullptr, false);
        if (mapOpen && invoke(scr, 0, "GetCurrentSection", 0, nullptr) != sec) continue;
        fitLive = mapOpen;
        bool placed = false;
        if (!mapOpen && !ptrF(managerInstance("CombatManager"), "currentEncounter") && findAll("MapScreen").size() == screens.size()) {
            void* m = api.class_get_method_from_name((void*)klassOf(scr), "PlacePositionCursor", 0);
            if (m) {
                *(int*)(scr + ctxOff) = ctx;
                void* exc = nullptr; api.runtime_invoke(m, (void*)scr, nullptr, &exc); placed = !exc;
                *(int*)(scr + ctxOff) = oldCtx;
            }
        }
        struct Bt { float r[4]; float w[3]; };
        std::vector<Bt> bts;
        for (P b : listRefs(ptrF(sec, "mapLocationButtons"))) { Bt t; if (!b) continue; rectOf(b, t.r); if (posOf(b, t.w)) bts.push_back(t); }
        float cr[4], cw[3];
        rectOf(pc, cr);
        if (bts.size() < 2 || !posOf(pc, cw)) continue;
        // what is drawn is the cursor's image; its middle is the pivot's world position plus (0.5 - pivot) * size
        P cimg = ptrF(pc, "cursorImage");
        P ctr = cimg ? invoke(cimg, 0, "get_transform", 0, nullptr) : 0;
        float pv[2] = {0.5f, 0.5f}, rc[4] = {0, 0, 0, 0}, iw[3];
        bool haveImg = false;
        if (ctr && posOf(cimg, iw)) {
            if (P pvb = invoke(ctr, 0, "get_pivot", 0, nullptr)) memcpy(pv, api.object_unbox((void*)pvb), 8);
            if (P rcb = invoke(ctr, 0, "get_rect", 0, nullptr)) memcpy(rc, api.object_unbox((void*)rcb), 16);
            haveImg = rc[2] > 0 && rc[3] > 0;
        }
        size_t ix1 = 0, ix2 = 0, iy1 = 0, iy2 = 0;
        for (size_t i = 0; i < bts.size(); i++) {
            if (bts[i].r[0] < bts[ix1].r[0]) ix1 = i; if (bts[i].r[0] > bts[ix2].r[0]) ix2 = i;
            if (bts[i].r[1] < bts[iy1].r[1]) iy1 = i; if (bts[i].r[1] > bts[iy2].r[1]) iy2 = i;
        }
        float dxr = bts[ix2].r[0] - bts[ix1].r[0], dyr = bts[iy2].r[1] - bts[iy1].r[1];
        float sx = dxr > 20 ? (bts[ix2].w[0] - bts[ix1].w[0]) / dxr : 0, sy = dyr > 20 ? (bts[iy2].w[1] - bts[iy1].w[1]) / dyr : 0;
        if (fabsf(sx) < 1e-6f || fabsf(sy) < 1e-6f) continue;
        if (haveImg) { cw[0] = iw[0]; cw[1] = iw[1]; }
        float chartX = bts[ix1].r[0] + (cw[0] - bts[ix1].w[0]) / sx;          // art pixels from the left edge
        float chartYup = bts[iy1].r[1] + (cw[1] - bts[iy1].w[1]) / sy;        // anchored-style: 0 at the top edge, negative downwards
        if (haveImg) { chartX += (0.5f - pv[0]) * rc[2]; chartYup += (0.5f - pv[1]) * rc[3]; }
        static int pl = 0; if (pl++ < 2) LOGI("worldmap cursor image: pivot %.2f,%.2f size %.0fx%.0f have %d", pv[0], pv[1], rc[2], rc[3], haveImg);
        char b2[96];
        snprintf(b2, sizeof b2, "[%.1f,%.1f]", chartX - cr[0], -chartYup + cr[1]);
        fitJson = b2;
        if (placed) { snprintf(b2, sizeof b2, "[%.1f,%.1f]", cr[0], cr[1]); pcJson = b2; }
        static int fl = 0; if (fl++ < 4) LOGI("worldmap fit: a %.1f b %.1f, party cursor %.1f,%.1f placed %d", chartX - cr[0], -chartYup + cr[1], cr[0], cr[1], placed);
    }
    fprintf(f, "],\"fit\":%s,%s,%s,\"pc\":%s,\"cursors\":[", fitJson.c_str(), kvb("fitLive", fitLive).c_str(), kv("fitSection", hereSection ? hereSection : "").c_str(), pcJson.c_str());
    bool fc = true;
    for (P scr : screens) {
        std::vector<P> cs = listRefs(ptrF(scr, "objectiveCursors"));
        cs.push_back(ptrF(scr, "playerPositionCursor"));
        for (size_t i = 0; i < cs.size(); i++) {
            P c = cs[i]; if (!c) continue;
            float r[4]; rectOf(c, r);
            P img = ptrF(c, "cursorImage");
            P sp = img ? invoke(img, 0, "get_sprite", 0, nullptr) : 0;
            P go = invoke(c, 0, "get_gameObject", 0, nullptr);
            fprintf(f, "%s{%s,%s,\"r\":[%.1f,%.1f],%s}", fc ? "" : ",", kv("kind", i + 1 == cs.size() ? "player" : "objective").c_str(), kv("sprite", sp ? safeName(objName(sp)) : "").c_str(),
                    r[0], r[1], kvb("shown", go ? invokeBool(go, "get_activeInHierarchy", 0, nullptr, false) : false).c_str());
            fc = false;
        }
    }
    fprintf(f, "]}");
    fclose(f);
    rename((g_dir + "/duo_worldmap.tmp").c_str(), (g_dir + "/duo_worldmap.json").c_str());
    static int wl = 0; if (wl++ < 3) LOGI("worldmap: unlocked %d, %zu map screens", unlocked, screens.size());
}

// ---- fast boot: run the game clock fast while the startup logos play, back to normal as soon as the title sequence
// exists (or the party is in a level). Only Time.timeScale is touched. Opt out with <files>/duo_nofastboot.txt.
void fastBoot() {
    extern long g_ticks;
    static int state = 0;                       // 0 = not started, 1 = sped up, 2 = finished
    if (state == 2) return;
    static void* timeK = findClass("UnityEngine.Time");
    if (!timeK) { state = 2; return; }
    bool ready = managerInstance("TitleSequenceManager") != 0 || g_leaderOk || g_ticks > 60 * 90;
    if (state == 0 && !ready) {
        if (!g_dir.empty() && fileExists(g_dir + "/duo_nofastboot.txt")) { state = 2; return; }
        float sc = 8; void* a[1] = {&sc};
        invoke(0, (P)timeK, "set_timeScale", 1, a);
        state = 1; LOGI("fast boot: logos at 8x");
    } else if (ready) {
        if (state == 1) { float sc = 1; void* a[1] = {&sc}; invoke(0, (P)timeK, "set_timeScale", 1, a); LOGI("fast boot: back to normal speed at tick %ld", g_ticks); }
        state = 2;
    }
}

typedef void (*UpdateFn)(P, const void*);
UpdateFn g_origUpdate = nullptr;
long g_ticks = 0;

void mainTick() {
    g_ticks++;
    if (g_ticks == 1) LOGI("main-thread tick running");
    std::vector<std::string> acts;
    { std::lock_guard<std::mutex> l(g_actMu); acts.swap(g_actions); }
    bool wantPortrait;
    { std::lock_guard<std::mutex> l(g_portMu); wantPortrait = !g_portraitWanted.empty(); }
    bool posTick = g_ticks % 2 == 0;   // also covers the battleExtras tick (18 is a multiple of 6)
    if (acts.empty() && !wantPortrait && g_zoom <= 0 && g_dumpQ.empty() && g_cacheQ.empty() && !posTick) return;
    std::unique_lock<std::recursive_mutex> lk(g_api, std::try_to_lock);
    if (lk.owns_lock()) fastBoot();
    if (lk.owns_lock() && g_wantSection && g_ticks % 6 == 0) sectionTick();
    if (lk.owns_lock() && g_ticks % 4 == 0) sampleLeader();
    if (lk.owns_lock() && g_ticks % (g_bx.empty() ? 18 : 6) == 0) battleExtras();   // ticks are frames (60/s)
    if (lk.owns_lock() && g_ticks % 120 == 60 && g_leaderOk) hudFaces();
    if (lk.owns_lock() && g_ticks % 300 == 150) fixPadGlyphs(false);   // again after the game reloads the sheet
    // Overworld window: drawing the world a second time every frame made the Thor run hot (fans at full, 2026-11-16).
    // The camera is switched on for one frame in every 20 (about 3 pictures a second); the display keeps the last
    // picture in between, which is plenty for a map.
    if (lk.owns_lock() && g_zoom > 0 && g_probeCam) {
        int ph = (int)(g_ticks % 30);
        // The display cycles through several buffers; drawing only one of them made it flicker between old and new
        // pictures. Draw the same picture for 4 frames in a row so every buffer holds it, then rest.
        if (ph == 0) { followMain(); bool on = true; void* ea[1] = {&on}; invokeOk(g_probeCam, "set_enabled", 1, ea); }
        else if (ph == 4) { bool off = false; void* ea[1] = {&off}; invokeOk(g_probeCam, "set_enabled", 1, ea); }
    }
    if (lk.owns_lock() && !g_dumpQ.empty()) dumpStep();
    if (lk.owns_lock() && !g_cacheQ.empty()) cacheStep();
    if (!lk.owns_lock()) {   // reader thread busy: retry next frame
        std::lock_guard<std::mutex> l(g_actMu);
        g_actions.insert(g_actions.begin(), acts.begin(), acts.end());
        return;
    }
    for (auto& a : acts) { if (a.rfind("dir:", 0) == 0) g_dir = a.substr(4); else if (a == "dump:ui") dumpStart(); else if (a == "cache:scan") cacheScan(); else if (a.rfind("md:", 0) == 0 || a.rfind("do:", 0) == 0) menuCmd(a); else if (a.rfind("press:", 0) == 0) pressTool(a.c_str() + 6); else if (a == "scan:worldmap") scanWorldMap(); else if (a.rfind("scan:level", 0) == 0) scanLevel(a.size() > 11 ? (float)atof(a.c_str() + 11) : 30.f, true); else if (a.rfind("scan:nav", 0) == 0) scanLevel(a.size() > 9 ? (float)atof(a.c_str() + 9) : 14.f, false); else if (a.rfind("map:", 0) == 0) { float z = 0, ox = 0, oy = 0; sscanf(a.c_str() + 4, "%f %f %f", &z, &ox, &oy); g_viewOx = ox; g_viewOy = oy; areaView(z); } else if (a == "probe:display") probeDisplay(); else if (a == "probe:stat") logDisplays(); else if (a == "hud:off" || a == "hud:on") hudVisible(a == "hud:on"); else if (a.rfind("hud:fade", 0) == 0) { g_fade = a.size() > 9 ? (float)atof(a.c_str() + 9) : 0.f; LOGI("hud fade %.2f", g_fade); } else if (a == "hud:show") { g_fade = 1; auto h = findAll("InGameHud"); auto b = findAll("BattleScreen"); P e = ptrF(managerInstance("CombatManager"), "currentEncounter"); fadeHud(h.empty() ? 0 : h[0], b.empty() ? 0 : b[0], e ? activeList(e) : 0); unfadeAll(); g_fade = -1; LOGI("hud shown"); } else if (a.rfind("probe:", 0) == 0) probeCmd(a); else doAction(a); }
    if (wantPortrait) {
        std::pair<std::string, P> job;
        { std::lock_guard<std::mutex> l(g_portMu); job = g_portraitWanted.back(); g_portraitWanted.pop_back(); }
        Portrait p;
        bool ok = readSprite(job.second, p);
        LOGI("portrait %s: %s %dx%d", job.first.c_str(), ok ? "ok" : "failed", p.w, p.h);
        std::lock_guard<std::mutex> l(g_portMu);
        if (!ok) { p.w = p.h = 1; p.rgba.assign(4, 0); }
        g_portraits[job.first] = std::move(p);
    }
}

void hookedUpdate(P self, const void* method) {
    g_origUpdate(self, method);
    mainTick();
}

void installMainThreadHook() {
    void* cls = findClass("InputManager");
    void* m = cls ? api.class_get_method_from_name(cls, "Update", 0) : nullptr;
    if (!m) { LOGE("InputManager.Update not found"); return; }
    P* mi = (P*)m;   // MethodInfo { methodPointer; virtualMethodPointer; invoker_method; name; ... }
    g_origUpdate = (UpdateFn)mi[0];
    if (mi[1] == mi[0]) mi[1] = (P)&hookedUpdate;
    mi[0] = (P)&hookedUpdate;
    LOGI("main-thread hook installed on InputManager.Update");
}

// reader thread: queue portraits we don't have yet
void requestPortrait(const std::string& id, P def) {
    std::lock_guard<std::mutex> l(g_portMu);
    if (g_portraits.count(id)) return;
    for (auto& w : g_portraitWanted) if (w.first == id) return;
    P sprite = ptrF(def, "miniPortrait");
    if (!sprite) sprite = ptrF(def, "microPortrait");
    if (sprite) g_portraitWanted.push_back({id, sprite});
}

// Title screen = LevelManager.currentLevel equals the game's static LevelReference.TitleScreen.
bool onTitleScreen(P lvm) {
    static std::string titleGuid;
    if (titleGuid.empty()) {
        void* cls = findClass("LevelReference");
        void* f = cls ? api.class_get_field_from_name(cls, "TitleScreen") : nullptr;
        P v[2] = {0, 0};
        if (f) api.field_static_get_value(f, v);   // struct { string levelDefinitionGuid; }
        titleGuid = str(v[0]);
        LOGI("title level guid '%s'", titleGuid.c_str());
    }
    if (!lvm || titleGuid.empty()) return false;
    return str(rd<P>(fieldAddr(lvm, "currentLevel"))) == titleGuid;
}

// ---------------------------------------------------------------- thread
void* readerMain(void*) {
    P base = 0; std::string path;
    while (!findLib("libil2cpp.so", base, path)) usleep(500 * 1000);
    auto ex = loadExports(path, base);
    LOGI("libil2cpp at %lx, %zu exports", (unsigned long)base, ex.size());
    bool ok = bind(ex, "il2cpp_get_corlib", api.get_corlib) && bind(ex, "il2cpp_domain_get", api.domain_get) &&
              bind(ex, "il2cpp_thread_attach", api.thread_attach) && bind(ex, "il2cpp_domain_get_assemblies", api.domain_get_assemblies) &&
              bind(ex, "il2cpp_assembly_get_image", api.assembly_get_image) && bind(ex, "il2cpp_image_get_name", api.image_get_name) &&
              bind(ex, "il2cpp_class_from_name", api.class_from_name) && bind(ex, "il2cpp_class_get_parent", api.class_get_parent) &&
              bind(ex, "il2cpp_class_get_field_from_name", api.class_get_field_from_name) &&
              bind(ex, "il2cpp_class_get_method_from_name", api.class_get_method_from_name) &&
              bind(ex, "il2cpp_field_get_offset", api.field_get_offset) && bind(ex, "il2cpp_field_static_get_value", api.field_static_get_value) &&
              bind(ex, "il2cpp_class_get_name", api.class_get_name) && bind(ex, "il2cpp_class_array_element_size", api.class_array_element_size) &&
              bind(ex, "il2cpp_class_get_element_class", api.class_get_element_class) && bind(ex, "il2cpp_runtime_invoke", api.runtime_invoke) &&
              bind(ex, "il2cpp_object_unbox", api.object_unbox) && bind(ex, "il2cpp_string_new", api.string_new) &&
              bind(ex, "il2cpp_class_get_fields", api.class_get_fields) && bind(ex, "il2cpp_field_get_flags", api.field_get_flags) &&
              bind(ex, "il2cpp_field_get_type", api.field_get_type) && bind(ex, "il2cpp_type_get_type", api.type_get_type) &&
              bind(ex, "il2cpp_class_from_type", api.class_from_type) && bind(ex, "il2cpp_class_is_subclass_of", api.class_is_subclass_of) &&
              bind(ex, "il2cpp_object_new", api.object_new) && bind(ex, "il2cpp_class_get_methods", api.class_get_methods) &&
              bind(ex, "il2cpp_method_get_param_count", api.method_get_param_count) && bind(ex, "il2cpp_method_get_param", api.method_get_param) &&
              bind(ex, "il2cpp_type_get_name", api.type_get_name) && bind(ex, "il2cpp_method_get_name", api.method_get_name) &&
              bind(ex, "il2cpp_class_get_type", api.class_get_type) && bind(ex, "il2cpp_type_get_object", api.type_get_object);
    if (!ok) { publishMsg("error", "il2cpp exports missing"); return nullptr; }
    bind(ex, "il2cpp_resolve_icall", api.resolve_icall);

    publishMsg("boot", "Game is starting...");
    while (!api.get_corlib()) usleep(500 * 1000);
    sleep(3);
    void* domain = api.domain_get();
    api.thread_attach(domain);
    size_t n = 0;
    void** asms = api.domain_get_assemblies(domain, &n);
    for (size_t i = 0; i < n; i++) {
        void* img = api.assembly_get_image(asms[i]);
        if (!img) continue;
        g_images.push_back(img);
        if (!strcmp(api.image_get_name(img), "Assembly-CSharp.dll")) g_image = img;
    }
    if (!g_image) { publishMsg("error", "Assembly-CSharp not found"); return nullptr; }

    installMainThreadHook();
    for (;;) {
        std::unique_lock<std::recursive_mutex> lk(g_api);
        if (!managerInstance("CharacterStatsManager") || !managerInstance("PlayerPartyManager")) {
            publishMsg("boot", "Waiting for the game...");
        } else {
            P ppm = managerInstance("PlayerPartyManager");
            P lvm = managerInstance("LevelManager");
            P enc = ptrF(managerInstance("CombatManager"), "currentEncounter");
            int cutscenes = valF<int>(managerInstance("CutsceneManager"), "cutsceneCount");
            if (onTitleScreen(lvm) || listElems(ptrF(ppm, "currentParty")).empty()) publishMsg("title", "");
            else if (valF<bool>(lvm, "loadingLevel")) publishMsg("loading", "");
            else if (cutscenes > 0 && !enc) publishMsg("cutscene", "");   // battles keep the battle panel
            else publish(snapshot());
        }
        lk.unlock();
        usleep((g_bx.empty() ? (g_leaderOk ? 100 : 200) : 50) * 1000);   // battles: refresh 20x a second so the mirrored menu keeps up with the cursor
    }
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_evtrai_sosduo_HostActivity_nativeStart(JNIEnv*, jclass) {
    static bool started = false;
    if (started) return;
    started = true;
    pthread_t t;
    pthread_create(&t, nullptr, readerMain, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_evtrai_sosduo_HostActivity_nativePoll(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_mu);   // raw UTF-8 bytes (NewStringUTF can't take 4-byte sequences)
    jbyteArray a = env->NewByteArray((jsize)g_json.size());
    env->SetByteArrayRegion(a, 0, (jsize)g_json.size(), reinterpret_cast<const jbyte*>(g_json.data()));
    return a;
}

extern "C" JNIEXPORT void JNICALL
Java_com_evtrai_sosduo_HostActivity_nativeAction(JNIEnv* env, jclass, jstring a) {
    const char* c = env->GetStringUTFChars(a, nullptr);
    { std::lock_guard<std::mutex> l(g_actMu); g_actions.push_back(c); }
    env->ReleaseStringUTFChars(a, c);
}

// [w(4) h(4) rgba...] with rows bottom-up, or null if not ready
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_evtrai_sosduo_HostActivity_nativePortrait(JNIEnv* env, jclass, jstring id) {
    const char* c = env->GetStringUTFChars(id, nullptr);
    std::string key = c;
    env->ReleaseStringUTFChars(id, c);
    std::lock_guard<std::mutex> l(g_portMu);
    auto it = g_portraits.find(key);
    if (it == g_portraits.end() || it->second.w <= 1) return nullptr;
    const Portrait& p = it->second;
    jbyteArray a = env->NewByteArray((jsize)(8 + p.rgba.size()));
    int32_t wh[2] = {p.w, p.h};
    env->SetByteArrayRegion(a, 0, 8, reinterpret_cast<const jbyte*>(wh));
    env->SetByteArrayRegion(a, 8, (jsize)p.rgba.size(), reinterpret_cast<const jbyte*>(p.rgba.data()));
    return a;
}
