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

    const id = window.location.toString().split('/')[
        window.location.toString().split('/').length - 1
    ];

    const response = await fetch(`/api/users/${id}`, {
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

                billName.innerHTML = bill.name;
                billAmount.innerHTML = bill.amount;
                billDelete.innerHTML = 'Delete'

                billDelete.classList.add('delete-btn');
                expenseItemsDiv.classList.add('expense-items');

                billDelete.setAttribute('id', `${bill.id}`)

                expenseItemsDiv.append(billName);
                expenseItemsDiv.append(billAmount);
                expenseItemsDiv.append(billDelete)
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
            }

            document.querySelectorAll('.delete-btn').forEach(btn => {
                btn.addEventListener('click', deleteBill)
            });

        })
    } else {
        alert(response.statusText)
    }
};

getExpenseHandler();


document.querySelector('.add-expense').addEventListener('click', addExpenseHandler);