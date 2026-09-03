#!/usr/bin/env python3
"""job_registry 唯一键迁移：留档 + 执行（dev 3306 与 loadtest 3307 通用）。

用法：
  python tools/registry_unique_key_migration.py count [--host H] [--port P] [--db DB]
  python tools/registry_unique_key_migration.py apply [--host H] [--port P] [--db DB] --yes

密码解析优先级：--password > 环境变量 WWJOB_DB_PASSWORD > application-local.yml（仅取 password 键，绝不打印）。
SQL 正文直接读取已提交的 db/migrate/2026-09-03-registry-unique-key.sql，本脚本不内嵌 SQL。
apply 先打印将被删行数供人工核对，需 --yes 才执行（DELETE 再 ALTER）。
"""
import argparse
import os
import re
import sys

import pymysql


def repo_root():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_local_password():
    path = os.path.join(repo_root(), "ww-job-admin", "src", "main", "resources", "application-local.yml")
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^\s*password:\s*(\S+)\s*$", line)
            if m:
                return m.group(1)
    return None


def resolve_password(args):
    if args.password:
        return args.password
    if os.environ.get("WWJOB_DB_PASSWORD"):
        return os.environ["WWJOB_DB_PASSWORD"]
    return read_local_password()


def migrate_sql():
    path = os.path.join(repo_root(), "ww-job-admin", "src", "main", "resources",
                        "db", "migrate", "2026-09-03-registry-unique-key.sql")
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    # 去掉整行注释后按分号切语句
    no_comments = "\n".join(l for l in text.splitlines() if not l.strip().startswith("--"))
    return [s.strip() for s in no_comments.split(";") if s.strip()]


def connect(args, password):
    return pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=password, database=args.db, charset="utf8mb4")


def count_rows(cur):
    cur.execute("SELECT COUNT(*) FROM job_registry")
    total = cur.fetchone()[0]
    cur.execute(
        "SELECT job_group_id, registry_value, COUNT(*) c "
        "FROM job_registry GROUP BY job_group_id, registry_value HAVING c > 1 "
        "ORDER BY c DESC")
    groups = cur.fetchall()
    to_delete = sum(g[2] - 1 for g in groups)
    return total, groups, to_delete


def main():
    p = argparse.ArgumentParser(description="job_registry 唯一键迁移留档/执行")
    p.add_argument("action", choices=["count", "apply"])
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--db", default="ww_job")
    p.add_argument("--user", default="root")
    p.add_argument("--password", default=None)
    p.add_argument("--yes", action="store_true")
    args = p.parse_args()

    password = resolve_password(args)
    if not password:
        print("未找到密码：--password / WWJOB_DB_PASSWORD / application-local.yml 三者至少其一", file=sys.stderr)
        return 2

    conn = connect(args, password)
    try:
        with conn.cursor() as cur:
            total, groups, to_delete = count_rows(cur)
            print(f"[{args.host}:{args.port}/{args.db}] job_registry 总行数={total}, 重复组数={len(groups)}, 将删行数={to_delete}")
            for (gid, val, c) in groups[:20]:
                print(f"  dup  group_id={gid} value={val} 行数={c}")

            if args.action == "apply":
                if not args.yes:
                    print("未加 --yes，不执行。将删行数见上，确认后加 --yes 重跑。")
                    return 1
                stmts = migrate_sql()
                print(f"执行 {len(stmts)} 条迁移语句（来自 db/migrate/2026-09-03-registry-unique-key.sql）...")
                for s in stmts:
                    cur.execute(s)
                    conn.commit()
                    print("  OK:", s.splitlines()[0][:80])
                cur.execute("SHOW INDEX FROM job_registry WHERE Key_name='uk_group_value'")
                if cur.fetchall():
                    print("唯一键 uk_group_value 已就位。")
                else:
                    print("!! 未找到 uk_group_value，迁移可能未生效", file=sys.stderr)
                    return 3
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
