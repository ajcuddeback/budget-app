async function addExpenseHandler(event) {
    const name = document.querySelector('#expenseName').value;
    const amount = document.querySelector('#expenseAmount').value;

    const response = await fetch('/api/bills', {
        method: 'POST',
        body: JSON.stringify({
            name,
            amount
        }),
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        document.location.reload();
    } else {
        alert(response.statusText)
    }
};

async function getExpenseHandler(event) {
    const response = await fetch(`/api/users/one`, {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        response.json().then(text => {
            const expenseDiv = document.querySelector('.expense-output-wrapper');
            const totalExpenseDiv = document.querySelector('.total-expense');
            totalExpenseDiv.innerHTML = ""

            const billArr = text.bills;
            let totalBills = 0;
            billArr.forEach(bill => {

                let expenseItemsDiv = document.createElement('div');
                let billName = document.createElement('p');
                let billAmount = document.createElement('p');
                let billDelete = document.createElement('button');
                let isPayedBtn = document.createElement('INPUT');

                billName.innerHTML = bill.name;
                billAmount.innerHTML = bill.amount;
                billDelete.innerHTML = 'Delete'

                billDelete.classList.add('delete-btn');
                expenseItemsDiv.classList.add('expense-items');
                isPayedBtn.classList.add('isPayedCheckbox')

                billDelete.setAttribute('id', `${bill.id}`);
                isPayedBtn.setAttribute('type', 'checkbox');
                isPayedBtn.setAttribute('id', `${bill.id}`);

                if (bill.is_payed === true) {
                    isPayedBtn.checked = true
                } else {
                    isPayedBtn.checked = false
                }

                expenseItemsDiv.append(billName);
                expenseItemsDiv.append(billAmount);
                expenseItemsDiv.append(billDelete);
                expenseItemsDiv.append(isPayedBtn);
                expenseDiv.append(expenseItemsDiv);

                totalBills = totalBills + parseInt(bill.amount);
            })

            totalExpenseDiv.innerHTML = `Total Expenses: ${totalBills}`;
            async function deleteBill(event) {

                const id = event.target.getAttribute('id');

                const response = await fetch(`/api/bills/${id}`, {
                    method: 'DELETE',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                })
                if (response.ok) {
                    document.location.reload();
                } else {
                    alert(response.statusText)
                }
            };

            async function isPayedHandler(event) {
                const id = event.target.getAttribute('id');
                let isPayed;

                if (event.target.checked) {
                    isPayed = true
                } else {
                    isPayed = false
                };

                const response = await fetch(`/api/bills/${id}`, {
                    method: 'PUT',
                    body: JSON.stringify({
                        isPayed
                    }),
                    headers: {
                        'Content-Type': 'application/json'
                    }
                })

                if (response.ok) {
                    document.location.reload();
                } else {
                    alert(response.statusText)
                }
            }

            document.querySelectorAll('.delete-btn').forEach(btn => {
                btn.addEventListener('click', deleteBill)
            });

            document.querySelectorAll('.isPayedCheckbox').forEach(checkbox => {
                checkbox.addEventListener('click', isPayedHandler)
            });

        })
    } else {
        alert(response.statusText)
    }
};

getExpenseHandler();


document.querySelector('.add-expense').addEventListener('click', addExpenseHandler);